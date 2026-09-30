"""drone-show (completion condition 1): the show drones appear while a hut is built, stay within the
configured maximum, vanish afterwards, and killing them mid-build does not stop the job."""
import time

from tools.p4 import harness
from tools.p4.scenarios_basic import DIMENSION, PLAYER, _command, _prepare_view, _screenshot, _submit_and_offer, _teleport

SHOW_TAG = "micradrone_build_show"
MAX_DRONES = 6  # BudgetConfig.MAX_DRONES (server default)
SAMPLE_INTERVAL_S = 0.05
FIRST_HUT_STAND = (300, -60, 0)
SECOND_HUT_STAND = (200, -60, 0)


def _drones(ctx):
    return ctx.server.post("/build/entities", {"dimension": DIMENSION, "tag": SHOW_TAG})


def _sample_until_terminal(ctx, job_id, samples, on_first_drone=None):
    """Sample drone count and job state every SAMPLE_INTERVAL_S until the job is terminal."""
    deadline = time.monotonic() + harness.JOB_TIMEOUT_S
    fired = False
    while True:
        status = ctx.server.post("/build/status", {"jobId": job_id})
        found = _drones(ctx)
        samples.append({"t": round(time.time(), 3), "state": status["state"], "cursor": status.get("cursor"),
                        "drones": found["count"], "positions": found["positions"]})
        if not fired and found["count"] >= 1 and on_first_drone is not None:
            fired = True
            on_first_drone()
        if status["state"] in ("VERIFIED", "PARTIAL", "FAILED", "CANCELLED", "ROLLED_BACK"):
            return status
        if time.monotonic() > deadline:
            raise TimeoutError(f"job {job_id} not terminal: {status}")
        time.sleep(SAMPLE_INTERVAL_S)


def drone_show(ctx):
    files = []
    _prepare_view(ctx)
    # first hut: stand on the ground, watch the drones, take a picture while they fly
    _teleport(ctx, *FIRST_HUT_STAND, 180, 20)
    time.sleep(2)
    pending = _submit_and_offer(ctx, {"source": "sample:hut", "here": True})
    approval = ctx.server.post("/build/approve", {"player": PLAYER, "hash": pending["hash"], "confirmTerraform": True})
    assert approval["approved"] is True, approval
    samples = []
    shots = []

    def snap():
        shots.append(_screenshot(ctx, "drone-mid"))

    final = _sample_until_terminal(ctx, approval["jobId"], samples, on_first_drone=snap)
    ctx.save_json("drones.json", samples)
    ctx.save_json("status.json", final)
    assert final["state"] == "VERIFIED", final
    counts = [s["drones"] for s in samples if s["state"] in ("RUNNING", "REPAIRING")]
    assert counts and max(counts) >= 1, f"no show drone was ever seen while building: {counts}"
    assert max(s["drones"] for s in samples) <= MAX_DRONES, "more drones than the configured maximum"
    after = _drones(ctx)
    ctx.save_json("drones-after.json", after)
    assert after["count"] == 0, f"{after['count']} show drones remain after VERIFIED"
    files += [ctx.out("drones.json"), ctx.out("drones-after.json")] + shots

    # second hut elsewhere: kill the drones mid-build, the job must still finish
    _teleport(ctx, *SECOND_HUT_STAND, 180, 20)
    time.sleep(2)
    pending2 = _submit_and_offer(ctx, {"source": "sample:hut", "here": True})
    approval2 = ctx.server.post("/build/approve", {"player": PLAYER, "hash": pending2["hash"], "confirmTerraform": True})
    assert approval2["approved"] is True, approval2
    kills = []

    def kill():
        kills.append(_command(ctx, f"kill @e[tag={SHOW_TAG}]"))

    samples2 = []
    final2 = _sample_until_terminal(ctx, approval2["jobId"], samples2, on_first_drone=kill)
    ctx.save_json("drones-killed.json", {"kill": kills, "samples": samples2})
    assert kills, "the drones never showed up in the second build, so nothing was killed"
    assert final2["state"] == "VERIFIED", f"killing the drones broke the job: {final2}"
    files.append(ctx.out("drones-killed.json"))
    return files
