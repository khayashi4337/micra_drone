"""Restart and crash recovery in the real game (singleplayer; the integrated server is the server that stops or dies).

restart-resume   a big hut is half built, the game window is closed (clean stop: the server saves and halts), the same world
                 is opened again: the job must be running again within RESUME_DEADLINE_S of entering the world and finish
                 VERIFIED with every block as planned.
real-crash-sp    the same, but the game process is killed (taskkill of the PID this run started, nothing else). Whatever
                 instant it died at, nothing may be silently re-run: either the job resumes, or it asks (RECOVERY_NEEDED) and
                 the script answers `discard`; it must end VERIFIED with no conflicts.
missing-journal  clean stop, then the job's journal.bin is deleted: after the restart the job must be PAUSED(RECOVERY_NEEDED),
                 place nothing for MISSING_JOURNAL_HOLD_S, tell the child in Japanese, and end FAILED when answered `fail`.

The dedicated-server variants (crash at every protocol boundary, real-crash on a server) need the owner's EULA file and are
not here; they stay pending (see PENDING_CONDITIONS).
"""
import subprocess
import time

from tools.p4 import devkit_client, harness, plans
from tools.p4.scenarios_basic import DIMENSION, PLAYER, TERMINAL_STATES, _prepare_view, _read_back_and_compare, _submit_and_offer, _teleport
from tools.p4.scenarios_l7 import _write_plan

BIG_WIDTH = 40
BIG_DEPTH = 40
BIG_FLOORS = 2
FACE_NORTH = 180
HALF_TIMEOUT_S = 180
RESUME_DEADLINE_S = 10.0
RESUME_POLL_S = 0.25
STATUS_POLL_S = 0.1
MISSING_JOURNAL_HOLD_S = 30
SHOW_TAG = "micradrone_build_show"
WORLD_DIR = harness.RUN_DIR / "client" / "saves" / "p4-auto"
JOBS_DIR = WORLD_DIR / "data" / "micradrone" / "jobs"
WAL_DIR = WORLD_DIR / "data" / "micradrone" / "wal"
STANDS = {"restart-resume": (1600, -60, 0), "real-crash-sp": (1800, -60, 0), "missing-journal": (2000, -60, 0)}


def _live_kind(game):
    for kind in ("sp", "sp-load"):
        proc = game.procs.get(kind)
        if proc is not None and proc.poll() is None:
            return kind
    raise RuntimeError("no live singleplayer game of this run")


def _start_big_build(ctx, name):
    sx, sy, sz = STANDS[name]
    patch = plans.hut_patch((sx, sy, sz), "north", width=BIG_WIDTH, depth=BIG_DEPTH, floors=BIG_FLOORS)
    source = _write_plan("p4-big-" + name, patch)
    _prepare_view(ctx)
    _teleport(ctx, sx, sy, sz, FACE_NORTH, 20)
    time.sleep(2)
    pending = _submit_and_offer(ctx, {"source": source, "here": True})
    approval = ctx.server.post("/build/approve", {"player": PLAYER, "hash": pending["hash"], "confirmTerraform": True})
    ctx.save_json("approval.json", approval)
    assert approval["approved"] is True, approval
    return approval["jobId"], pending["hash"]


def _wait_half(ctx, job_id):
    """Polls until more than half of the job's placements are done; fails if the job is already over (too fast to interrupt)."""
    deadline = time.monotonic() + HALF_TIMEOUT_S
    while True:
        st = ctx.server.post("/build/status", {"jobId": job_id})
        if st["total"] > 0 and st["cursor"] * 2 > st["total"]:
            return st
        assert st["state"] not in TERMINAL_STATES, f"the job ended before it could be interrupted: {st}"
        assert time.monotonic() < deadline, f"the job never passed half: {st}"
        time.sleep(STATUS_POLL_S)


def _relaunch(ctx):
    """Starts the same world again; returns the time.monotonic() at which the client reported being in the world."""
    game = ctx.game
    game.start_client("sp-load")
    game.wait_api("sp-load")
    game.wait_in_world(ctx.client)
    entered = time.monotonic()
    ctx.server.poll("/server/state", {}, lambda r: r.get("ready") is True and any(p["name"] == PLAYER for p in r["players"]),
                    harness.IN_WORLD_TIMEOUT_S)
    return entered


def _wait_running(ctx, job_id, entered, samples):
    """Polls /build/status from entering the world until RUNNING (or anything past it, or a pause). Returns (status, seconds)."""
    while True:
        try:
            st = ctx.server.post("/build/status", {"jobId": job_id})
        except devkit_client.DevkitError as unknown:  # the job record is not there yet
            st = {"state": "NOT-LOADED", "error": str(unknown)}
        samples.append({"t": round(time.monotonic() - entered, 2), "state": st.get("state"), "pause": st.get("pause"),
                        "cursor": st.get("cursor")})
        if st.get("state") in ("RUNNING", "VERIFYING", "REPAIRING", "VERIFIED", "PAUSED", "FAILED"):
            return st, time.monotonic() - entered
        assert time.monotonic() - entered < 60, f"the job never came back: {samples[-3:]}"
        time.sleep(RESUME_POLL_S)


def _wait_running_state(ctx, job_id, samples, wanted, timeout=60):
    """Polls until the job is in one of `wanted`; a PAUSED counts only when it is the recovery question (RECOVERY_NEEDED), not
    a wait for chunks (CHUNK_UNLOADED), which ends by itself once the child is back."""
    started = time.monotonic()
    while True:
        st = ctx.server.post("/build/status", {"jobId": job_id})
        samples.append({"t": round(time.monotonic() - started, 2), "state": st.get("state"), "pause": st.get("pause"),
                        "cursor": st.get("cursor")})
        if st["state"] in wanted and (st["state"] != "PAUSED" or st.get("pause") == "RECOVERY_NEEDED"):
            return st, time.monotonic() - started
        assert time.monotonic() - started < timeout, f"the job did not go on after the child came back: {samples[-3:]}"
        time.sleep(RESUME_POLL_S)


def _drones_now(ctx):
    return ctx.server.post("/build/entities", {"dimension": DIMENSION, "tag": SHOW_TAG})["count"]


def restart_resume(ctx):
    job_id, manifest_hash = _start_big_build(ctx, "restart-resume")
    before = _wait_half(ctx, job_id)
    ctx.save_json("before-stop.json", before)
    ctx.game.close_client(_live_kind(ctx.game))  # WM_CLOSE: the integrated server saves and halts
    entered = _relaunch(ctx)
    drones_after_load = _drones_now(ctx)
    samples = []
    st, seconds = _wait_running(ctx, job_id, entered, samples)
    ctx.save_json("resume.json", {"secondsToRunning": round(seconds, 2), "state": st.get("state"), "pause": st.get("pause"),
                                  "cursorBefore": before["cursor"], "cursorAfter": st.get("cursor"), "total": before["total"],
                                  "showDronesRightAfterLoad": drones_after_load, "samples": samples})
    assert st["state"] in ("RUNNING", "VERIFYING", "REPAIRING", "VERIFIED"), f"the job did not resume by itself: {st}"
    assert seconds <= RESUME_DEADLINE_S, f"resume took {seconds:.1f}s (limit {RESUME_DEADLINE_S}s)"
    final, seen = ctx.server.poll("/build/status", {"jobId": job_id}, lambda r: r["state"] in TERMINAL_STATES, harness.JOB_TIMEOUT_S)
    ctx.save_json("status.json", final)
    assert final["state"] == "VERIFIED", final
    assert final["conflicts"] == 0, final
    _read_back_and_compare(ctx, manifest_hash)
    return [ctx.out("before-stop.json"), ctx.out("resume.json"), ctx.out("status.json"), ctx.out("compare.json")]


def _wal_listing():
    return {p.name: p.stat().st_size for p in sorted(WAL_DIR.glob("*"))} if WAL_DIR.exists() else {}


def real_crash_sp(ctx):
    job_id, manifest_hash = _start_big_build(ctx, "real-crash-sp")
    before = _wait_half(ctx, job_id)
    kind = _live_kind(ctx.game)
    proc = ctx.game.procs[kind]
    assert proc in ctx.game.children, "only a process this run started may be killed"
    subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"], capture_output=True, timeout=60)
    proc.wait(timeout=60)
    ctx.save_json("crash.json", {"killedPid": proc.pid, "before": before, "wal": _wal_listing(),
                                 "jobFiles": sorted(p.name for p in (JOBS_DIR / job_id).glob("*")) if (JOBS_DIR / job_id).exists() else []})
    entered = _relaunch(ctx)
    # a killed game comes back where the world was last saved (near the spawn), not where the child stood: the job waits for
    # the site's chunks (PAUSED CHUNK_UNLOADED, by design). Walk back to the site and it goes on.
    samples = []
    first_seen, _ = _wait_running(ctx, job_id, entered, samples)
    sx, sy, sz = STANDS["real-crash-sp"]
    _teleport(ctx, sx, sy, sz, FACE_NORTH, 20)
    wanted = ("RUNNING", "VERIFYING", "REPAIRING", "VERIFIED", "FAILED", "PAUSED")
    st, seconds = _wait_running_state(ctx, job_id, samples, wanted)
    answered = None
    if st.get("pause") == "RECOVERY_NEEDED":
        answered = ctx.server.post("/build/recover", {"player": PLAYER, "jobId": job_id, "choice": "discard"})
    ctx.save_json("resume.json", {"secondsToFirstState": round(seconds, 2), "firstState": st.get("state"), "pause": st.get("pause"),
                                  "answered": answered, "samples": samples})
    final, seen = ctx.server.poll("/build/status", {"jobId": job_id}, lambda r: r["state"] in TERMINAL_STATES, harness.JOB_TIMEOUT_S)
    ctx.save_json("status.json", final)
    assert final["state"] == "VERIFIED", f"after a real crash the job ended {final['state']}: {final}"
    assert final["conflicts"] == 0, final
    _read_back_and_compare(ctx, manifest_hash)
    return [ctx.out("crash.json"), ctx.out("resume.json"), ctx.out("status.json"), ctx.out("compare.json")]


def missing_journal(ctx):
    job_id, manifest_hash = _start_big_build(ctx, "missing-journal")
    before = _wait_half(ctx, job_id)
    ctx.game.close_client(_live_kind(ctx.game))
    journal = JOBS_DIR / job_id / "journal.bin"
    assert journal.exists(), f"the job has no journal file on disk: {sorted(p.name for p in JOBS_DIR.glob('*'))}"
    journal.unlink()
    entered = _relaunch(ctx)
    since = 0  # the ask line is sent when the owner joins, i.e. before this point: read this client session from its start
    samples = []
    st, seconds = _wait_running(ctx, job_id, entered, samples)
    assert st["state"] == "PAUSED" and st.get("pause") == "RECOVERY_NEEDED", f"a missing journal must pause for recovery: {st}"
    first = _matching_blocks(ctx, manifest_hash)
    time.sleep(MISSING_JOURNAL_HOLD_S)
    held = ctx.server.post("/build/status", {"jobId": job_id})
    second = _matching_blocks(ctx, manifest_hash)
    chat = ctx.client.post("/chat-log", {"since": since})
    ctx.save_json("hold.json", {"before": before, "first": st, "held": held, "matchingBlocks": [first, second], "chat": chat["lines"]})
    assert held["cursor"] == st["cursor"] and held["pause"] == "RECOVERY_NEEDED", f"the job moved while waiting for an answer: {held}"
    assert first == second, f"the world changed while the job waited for an answer ({first} -> {second}): nothing may be re-run silently"
    assert any("わからない" in line["text"] and "recover" in line["text"] for line in chat["lines"]), \
        f"the child was not told in Japanese: {[l['text'] for l in chat['lines']][-4:]}"
    answer = ctx.server.post("/build/recover", {"player": PLAYER, "jobId": job_id, "choice": "fail"})
    final, seen = ctx.server.poll("/build/status", {"jobId": job_id}, lambda r: r["state"] in TERMINAL_STATES, 60)
    ctx.save_json("answer.json", {"answer": answer, "final": final})
    assert final["state"] == "FAILED", final
    return [ctx.out("hold.json"), ctx.out("answer.json")]


def _matching_blocks(ctx, manifest_hash):
    """How many planned positions currently hold the planned block id (a cheap 'did the world change' count)."""
    manifest = ctx.server.post("/build/manifest", {"hash": manifest_hash})
    last = {}
    for p in manifest["placements"]:
        last[tuple(p["pos"])] = p["block"]["id"]
    positions = sorted(last)
    count = 0
    for start in range(0, len(positions), 4096):
        chunk = positions[start:start + 4096]
        blocks = ctx.server.post("/build/read-blocks", {"dimension": DIMENSION, "positions": [list(p) for p in chunk]})["blocks"]
        count += sum(1 for pos, b in zip(chunk, blocks) if b["state"].split("[")[0] == last[pos])
    return count
