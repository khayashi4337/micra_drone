"""farm-regression (completion condition 11): the existing farm drone still works, alone and while a construction job runs.

The script text comes from the mod's own SampleScripts.TILL_AND_PLANT (read from the source file, not copied), so the
check follows the shipped sample. The plot is a 3x3 field: a corner marker four diagonal steps from the controller
(CornerMarkerScan: distance - 1 = 3 cells; the controller's and the marker's own rows are not part of the plot).
"""
import re
import textwrap
import time

from tools.p4 import harness
from tools.p4.scenarios_basic import DIMENSION, PLAYER, TERMINAL_STATES, _command, _prepare_view, _read_block, _screenshot, \
    _submit_and_offer, _teleport

SAMPLE_SCRIPTS = harness.REPO / "src" / "main" / "java" / "io" / "github" / "khayashi4337" / "micradrone" / "drone" / "SampleScripts.java"
GRASS_Y = -60
GROUND_Y = GRASS_Y - 1
FARM_A = (1200, GRASS_Y, 0)
FARM_B = (1280, GRASS_Y, 10)  # within the 12-block reach of HUT_STAND (RunScriptPayload), south of the hut (it is built to the north)
HUT_STAND = (1280, GRASS_Y, 0)
MARKER_STEPS = 4
PLOT_CELLS = MARKER_STEPS - 1
FARM_TIMEOUT_S = 240
FIRST_ATTEMPT_S = 90  # one silent non-start was seen in 1 of 3 real runs (script thread never printed): re-run once and RECORD it
POLL_S = 2.0
FACE_NORTH = 180


def till_and_plant_script():
    source = SAMPLE_SCRIPTS.read_text(encoding="utf-8")
    body = re.search(r'TILL_AND_PLANT = """\n(.*?)"""', source, re.S).group(1)
    return textwrap.dedent(body)


def _place_farm(ctx, origin):
    cx, cy, cz = origin
    _teleport(ctx, cx, cy + 2, cz, FACE_NORTH, 20)
    time.sleep(2)
    _command(ctx, f"fill {cx - 1} {GROUND_Y} {cz - 1} {cx + MARKER_STEPS + 1} {GROUND_Y} {cz + MARKER_STEPS + 1} minecraft:dirt")
    _command(ctx, f"setblock {cx} {cy} {cz} micradrone:drone_controller")
    _command(ctx, f"setblock {cx + MARKER_STEPS} {cy} {cz + MARKER_STEPS} micradrone:corner_marker")
    time.sleep(1)
    return [(cx + i, GROUND_Y, cz + j) for i in range(1, PLOT_CELLS + 1) for j in range(1, PLOT_CELLS + 1)]


def _run_farm_script(ctx, origin):
    cx, cy, cz = origin
    ctx.client.post("/open-ide", {"x": cx, "y": cy, "z": cz})
    ctx.client.post("/set-editor-text", {"text": till_and_plant_script()})
    ctx.client.post("/save", {})
    ctx.client.post("/run", {})


def _farmland_count(ctx, cells):
    blocks = ctx.server.post("/build/read-blocks", {"dimension": DIMENSION, "positions": [list(c) for c in cells]})["blocks"]
    return sum(1 for b in blocks if b["state"].startswith("minecraft:farmland")), blocks


def _wait_farmland(ctx, cells, rerun=None, notes=None):
    """Waits for every cell to become farmland. If a `rerun` callable is given and nothing happened within FIRST_ATTEMPT_S,
    it is called once and the fact is appended to `notes` (evidence), never hidden."""
    started = time.monotonic()
    deadline = started + FARM_TIMEOUT_S
    rerun_done = rerun is None
    while True:
        count, blocks = _farmland_count(ctx, cells)
        if count == len(cells):
            return blocks
        if not rerun_done and count == 0 and time.monotonic() - started > FIRST_ATTEMPT_S:
            rerun_done = True
            notes.append(f"no cell changed within {FIRST_ATTEMPT_S}s: the script was started once more")
            rerun()
        if time.monotonic() > deadline:
            raise AssertionError(f"only {count} of {len(cells)} cells became farmland: {[b['state'] for b in blocks]}")
        time.sleep(POLL_S)


def farm_regression(ctx):
    files = []
    _prepare_view(ctx)
    # (1) the farm script alone
    cells_a = _place_farm(ctx, FARM_A)
    _run_farm_script(ctx, FARM_A)
    notes = []
    blocks_a = _wait_farmland(ctx, cells_a, rerun=lambda: _run_farm_script(ctx, FARM_A), notes=notes)
    ctx.save_json("farm-alone.json", {"blocks": blocks_a, "notes": notes})
    files.append(ctx.out("farm-alone.json"))
    ctx.client.post("/close-screen", {})
    # (2) the farm script while a hut is being built
    cells_b = _place_farm(ctx, FARM_B)
    _teleport(ctx, *HUT_STAND, FACE_NORTH, 20)
    time.sleep(2)
    pending = _submit_and_offer(ctx, {"source": "sample:hut", "here": True})
    approval = ctx.server.post("/build/approve", {"player": PLAYER, "hash": pending["hash"], "confirmTerraform": True})
    assert approval["approved"] is True, approval
    _run_farm_script(ctx, FARM_B)
    blocks_b = _wait_farmland(ctx, cells_b)
    final, seen = ctx.server.poll("/build/status", {"jobId": approval["jobId"]}, lambda r: r["state"] in TERMINAL_STATES,
                                  harness.JOB_TIMEOUT_S)
    ctx.save_json("farm-with-build.json", {"farm": blocks_b, "job": final})
    files.append(ctx.out("farm-with-build.json"))
    assert final["state"] == "VERIFIED", f"the build must still finish while the farm runs: {final}"
    ctx.client.post("/close-screen", {})
    files.append(_screenshot(ctx, "farm-regression"))
    return files
