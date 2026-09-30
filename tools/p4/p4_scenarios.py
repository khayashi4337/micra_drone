"""Entry point: python -m tools.p4.p4_scenarios --all   (run from the repository root).

One command: preflight -> prepare -> start game -> world -> scenarios -> logs -> close -> cleanup -> summary.
Exit code 1 if any scenario FAILed or was NOT-RUN. NOT-RUN is never a pass.
"""
import argparse
import sys
import time
import traceback
from dataclasses import dataclass

from tools.p4 import devkit_client, evidence, harness, scenarios_approval, scenarios_basic, scenarios_l7, scenarios_show

MODES = ("sp", "mp", "mp2")
RESTART_LIMIT = 1


@dataclass(frozen=True)
class Scenario:
    func: object
    conditions: tuple
    mode: str
    owner_only_reason: "str | None" = None


SCENARIOS = {
    "devkit-smoke": Scenario(scenarios_basic.devkit_smoke, (), "sp"),
    "hut-golden": Scenario(scenarios_basic.hut_golden, (1,), "sp"),
    "approve-guard": Scenario(scenarios_approval.approve_guard, (5,), "sp"),
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
    11: "Task 37", 13: "Task 30", 14: "Task 28",
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


def run_mode_sp(game, folder, names):
    restarts = 0
    client = server = None
    for name in names:
        scenario = SCENARIOS[name]
        try:
            if client is None:
                client, server = _start_singleplayer(game, folder.run_id)
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
                    game.procs.get("sp") is None or game.procs["sp"].poll() is not None:
                restarts += 1
                client = server = None
                if restarts > RESTART_LIMIT:
                    for rest in names[names.index(name) + 1:]:
                        folder.record(rest, SCENARIOS[rest].conditions, evidence.NOT_RUN, "game crashed twice")
                    return


def run(args):
    harness.main_guard()
    run_id = args.run_id or time.strftime("p4-%Y%m%d-%H%M%S")
    folder = evidence.RunFolder(run_id, partial=PARTIAL_CONDITIONS)
    game = harness.Game(run_id, folder)
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
            run_mode_sp(game, folder, sp)
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
    return run(parser.parse_args(argv))


if __name__ == "__main__":
    sys.exit(main())
