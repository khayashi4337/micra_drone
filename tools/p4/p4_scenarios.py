"""Entry point: python -m tools.p4.p4_scenarios --all   (run from the repository root).

One command: preflight -> prepare -> start game -> world -> scenarios -> logs -> close -> cleanup -> summary.
Exit code 1 if any scenario FAILed or was NOT-RUN. NOT-RUN is never a pass.
"""
import argparse
import sys
import time
import traceback
from dataclasses import dataclass

from tools.p4 import devkit_client, evidence, harness, scenarios_approval, scenarios_basic, scenarios_l7, scenarios_mvp, scenarios_regression, scenarios_restart, scenarios_show

MODES = ("sp", "mp", "mp2")
RESTART_LIMIT = 1


@dataclass(frozen=True)
class Scenario:
    func: object
    conditions: tuple
    mode: str
    owner_only_reason: "str | None" = None
    claude: "str | None" = None  # the Claude mode the game needs (stub / real / none); None = the --claude option
    disruptive: bool = False  # restarts or kills the game itself: runs last in its group


SCENARIOS = {
    "devkit-smoke": Scenario(scenarios_basic.devkit_smoke, (), "sp"),
    "hut-golden": Scenario(scenarios_basic.hut_golden, (1,), "sp"),
    "approve-guard": Scenario(scenarios_approval.approve_guard, (5,), "sp"),
    "farm-regression": Scenario(scenarios_regression.farm_regression, (11,), "sp"),
    "restart-resume": Scenario(scenarios_restart.restart_resume, (), "sp", disruptive=True),
    "real-crash-sp": Scenario(scenarios_restart.real_crash_sp, (), "sp", disruptive=True),
    "missing-journal": Scenario(scenarios_restart.missing_journal, (), "sp", disruptive=True),
    "mvp-japanese-hut": Scenario(scenarios_mvp.mvp_japanese_hut, (), "sp"),
    "mvp-site-blocked": Scenario(scenarios_mvp.mvp_site_blocked, (), "sp"),
    "mvp-japanese-hut-real": Scenario(scenarios_mvp.mvp_japanese_hut, (), "sp", claude=harness.CLAUDE_REAL),
    "mvp-cli-missing": Scenario(scenarios_mvp.mvp_cli_missing, (), "sp", claude=harness.CLAUDE_NONE),
    "hut-here": Scenario(scenarios_basic.hut_here, (1, 12), "sp"),
    "bad-source": Scenario(scenarios_basic.bad_source, (), "sp"),
    "drone-show": Scenario(scenarios_show.drone_show, (1,), "sp"),
    "l7-repair": Scenario(scenarios_l7.l7_repair, (2, 15), "sp"),
    "l7-partial": Scenario(scenarios_l7.l7_partial, (2,), "sp"),
    "terrain-slope": Scenario(scenarios_l7.terrain_slope, (12,), "sp"),
    "safety-limits": Scenario(scenarios_l7.safety_limits, (8,), "sp"),
    "survey-pinned": Scenario(scenarios_l7.survey_pinned, (16,), "sp"),
}

# Design 07 completion conditions with no scenario yet -> the task that completes them.
# A task that adds its scenario removes its own condition here in the same commit.
PENDING_CONDITIONS = {
    3: "Task 29", 4: "Task 28", 6: "Task 34", 7: "Task 28", 9: "Task 32", 10: "Task 33",
    13: "Task 30", 14: "Task 28",
}


# Conditions whose scenarios pass but do not cover the whole condition text -> what is missing and who adds it.
# The condition shows as PARTIAL in summary.json until its task removes it here (Task 38 requires this to be empty).
PARTIAL_CONDITIONS = {
    8: "Task 30: block-entity blocks in general (only a chest with items is exercised so far)",
    12: "Task 27: survival - cut blocks gathered to the owner, fill blocks consumed, nothing created or lost",
}


def _select(args):
    names = list(SCENARIOS) if args.all else [n.strip() for n in args.only.split(",")]
    unknown = [n for n in names if n not in SCENARIOS]
    if unknown:
        raise SystemExit(f"unknown scenarios: {unknown}; known: {sorted(SCENARIOS)}")
    return [n for n in names if args.mode == "all" or SCENARIOS[n].mode == args.mode]


def _start_singleplayer(game, run_id, kind="sp"):
    game.start_client(kind)
    game.wait_api(kind)
    client = devkit_client.Devkit(harness.CLIENT_API_PORT, run_id)
    if kind == "sp":
        game.wait_screen(client, "TitleScreen")
        client.post("/create-world", {"name": "p4-auto", "seed": 4337, "flat": True, "gameMode": "creative"})
    game.wait_in_world(client)
    server = devkit_client.Devkit(harness.SERVER_API_PORT, run_id)
    server.poll("/server/state", {}, lambda r: r.get("ready") is True and
                any(p["name"] == scenarios_basic.PLAYER for p in r["players"]), harness.IN_WORLD_TIMEOUT_S)
    server.post("/server/run-command", {"command": f"op {scenarios_basic.PLAYER}"})
    state = server.post("/server/state")
    player = next(p for p in state["players"] if p["name"] == scenarios_basic.PLAYER)
    if player.get("op") is not True:
        raise RuntimeError(f"{scenarios_basic.PLAYER} is not op after the op command: {player}")
    return client, server


def _start_with_one_retry(game, folder, kind):
    """A NeoForge start sometimes dies before its API answers (seen once: 'unbound value neoforge:swim_speed' while modded
    registries load). Retry ONCE and write the fact down; a second crash is a real failure."""
    try:
        return _start_singleplayer(game, folder.run_id, kind)
    except harness.GameCrashedError as first:
        folder.write_text(f"start-retry-{kind}.txt", f"the game crashed before its API answered: {first}\nretrying once\n")
        game.collect_logs(target=f"logs/crashed-start-{kind}")
        return _start_singleplayer(game, folder.run_id, kind)


def _groups_by_claude(names, default):
    """Scenario names grouped by the Claude mode their game needs, in order of first appearance."""
    groups = {}
    for name in names:
        groups.setdefault(SCENARIOS[name].claude or default, []).append(name)
    for group in groups.values():
        group.sort(key=lambda n: SCENARIOS[n].disruptive)  # stable: the rest keep their order, the disruptive ones go last
    return groups


def _attach_or_start(game, folder, kind):
    """Reuses a live singleplayer game (a scenario may have restarted it) instead of starting a second one."""
    for live in ("sp", "sp-load"):
        proc = game.procs.get(live)
        if proc is not None and proc.poll() is None:
            return (devkit_client.Devkit(harness.CLIENT_API_PORT, folder.run_id),
                    devkit_client.Devkit(harness.SERVER_API_PORT, folder.run_id))
    return _start_with_one_retry(game, folder, kind)


def run_mode_sp(game, folder, names, default_claude=harness.CLAUDE_STUB):
    """Runs singleplayer scenarios. The game's PATH decides which `claude` answers, so scenarios that need another Claude
    mode run in their own game start (the saved world p4-auto is re-opened with --quickPlaySingleplayer)."""
    restarts = 0
    client = server = None
    kind = "sp"
    for group_index, (mode, group) in enumerate(_groups_by_claude(names, default_claude).items()):
        if group_index > 0:
            try:
                game.collect_logs(target=f"logs/before-group-{group_index}")  # the next start rotates latest.log away
                game.close_client(kind)
            except Exception:
                traceback.print_exc()
            client = server = None
            kind = "sp-load"
        game.claude_mode = mode
        for name in group:
            scenario = SCENARIOS[name]
            try:
                if client is None:
                    client, server = _attach_or_start(game, folder, kind)
                ctx = scenarios_basic.Ctx(name, folder, game, client, server)
                files = scenario.func(ctx)
                folder.record(name, scenario.conditions, evidence.PASS, "", files)
                print(f"PASS  {name}")
            except Exception as e:
                traceback.print_exc()
                folder.write_text(f"{name}/failure.txt", traceback.format_exc())
                folder.record(name, scenario.conditions, evidence.FAIL, f"{type(e).__name__}: {e}"[:500])
                print(f"FAIL  {name}: {e}")
                if isinstance(e, (harness.GameCrashedError, devkit_client.ForeignGameError)) or \
                        game.procs.get(kind) is None or game.procs[kind].poll() is not None:
                    restarts += 1
                    client = server = None
                    if restarts > RESTART_LIMIT:
                        pending = [n for n in names if n not in folder.scenarios]
                        for rest in pending:
                            folder.record(rest, SCENARIOS[rest].conditions, evidence.NOT_RUN, "game crashed twice")
                        return


def run(args):
    harness.main_guard()
    run_id = args.run_id or time.strftime("p4-%Y%m%d-%H%M%S")
    folder = evidence.RunFolder(run_id, partial=PARTIAL_CONDITIONS)
    game = harness.Game(run_id, folder, claude_mode=args.claude)
    names = _select(args)
    try:
        harness.preflight()
        game.clear_worlds()
        game.prepare()
        free = harness.free_physical_gib()
        folder.write_json("memory.json", {"freeGiB": free, "top": harness.top_processes_by_memory()})
        sp = [n for n in names if SCENARIOS[n].mode == "sp"]
        others = [n for n in names if SCENARIOS[n].mode != "sp"]
        if sp:
            run_mode_sp(game, folder, sp, default_claude=args.claude)
        for name in others:
            reason = "eula" if not game.eula_present() else "not implemented yet"
            folder.record(name, SCENARIOS[name].conditions, evidence.NOT_RUN, reason)
    except Exception as e:
        traceback.print_exc()
        folder.write_text("run-failure.txt", traceback.format_exc())
        for name in names:
            if name not in folder.scenarios:
                folder.record(name, SCENARIOS[name].conditions, evidence.NOT_RUN, f"run aborted: {type(e).__name__}: {e}"[:500])
    finally:
        try:
            folder.write_json("memory-at-end.json", game.memory_snapshot())
            game.collect_logs()
        except Exception:
            traceback.print_exc()
        try:
            for kind in ("sp", "sp-load", "mp", "mp2"):
                if kind in game.procs:
                    game.close_client(kind)
            game.stop_server()
        except Exception:
            traceback.print_exc()
        game.cleanup()
    summary = folder.write_summary()
    print(f"evidence: {folder.path}")
    for name, entry in summary["scenarios"].items():
        print(f"{entry['status']:8} {name} {entry['reason']}")
    return 0 if folder.all_passed() else 1


def main(argv=None):
    parser = argparse.ArgumentParser(prog="python -m tools.p4.p4_scenarios")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--all", action="store_true")
    group.add_argument("--only", help="comma-separated scenario names")
    parser.add_argument("--mode", choices=("sp", "mp", "mp2", "all"), default="all")
    parser.add_argument("--run-id")
    parser.add_argument("--claude", choices=(harness.CLAUDE_STUB, harness.CLAUDE_REAL, harness.CLAUDE_NONE), default=harness.CLAUDE_STUB,
                        help="stub: a canned claude.cmd answers (deterministic); real: the installed Claude CLI answers")
    return run(parser.parse_args(argv))


if __name__ == "__main__":
    sys.exit(main())
