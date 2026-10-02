"""Cancel and rollback in the real game (singleplayer; creative, so no material is returned).

cancel    a big hut is half built and cancelled: the job ends CANCELLED, what was placed STAYS (a cancel does not undo), and the
          site is still claimed (another player's overlapping plan is cancelled with E-CLAIM-OVERLAP).
rollback  a hut on grass (with terrain work) is built, one wall block is replaced by a gold block, and the claim is rolled back:
          the preview starts nothing; the confirmed rollback ends PARTIAL (the gold block stays, one conflict); every other
          position is back to what the world held BEFORE the build (block and block state); no item entity was dropped;
          the claim is still held; after the gold block is put back, a second rollback ends VERIFIED, the site equals the
          before-picture and the claim is released.

The "other player" is a FakePlayer named Intruder (the devkit resolves an offline name that way); it submits a plan file whose
site is exactly the first player's site.
"""
import time

from tools.p4 import devkit_client, harness, plans
from tools.p4.scenarios_basic import (DIMENSION, PLAYER, TERMINAL_STATES, _prepare_view, _read_back_and_compare, _run_job,
                                      _screenshot, _submit_and_offer, _teleport)
from tools.p4.scenarios_l7 import GOLD, _setblock, _state_text, _write_plan
from tools.p4.scenarios_restart import (BIG_DEPTH, BIG_FLOORS, BIG_WIDTH, FACE_NORTH, _start_big_build, _wait_half)

INTRUDER = "Intruder"
CANCEL_STAND = (2200, -60, 0)
ROLLBACK_STAND = (2400, -60, 0)
BEFORE_BOX = (-8, -4, -20, 20, 14, 20)  # relative to the stand: the whole hut and its terrain work, with margin
READ_CHUNK = 4096
SETTLE_S = 2
NEAR_SITE = 4  # the first player stands this far inside the other plan's site so its chunks are loaded
# The other player's small hut must overlap the claim's OPERATING box (it reaches 5 blocks beyond the walls) without standing
# on any placed block (else it is refused earlier for "blocks that cannot be replaced", not for the claim).
EDGE_GAP = 3  # cancel: the big hut is BIG_WIDTH wide; the intruder starts 3 blocks past its east wall
RB_EDGE = 17  # rollback: the default hut's box reaches x+15; the intruder's own box starts at origin-5
OVERLAP_CODE = "E-CLAIM-OVERLAP"


def _read_box(ctx, stand):
    """Every block of BEFORE_BOX around `stand` as {(x,y,z): state}."""
    sx, sy, sz = stand
    x1, y1, z1, x2, y2, z2 = BEFORE_BOX
    positions = [(sx + x, sy + y, sz + z) for y in range(y1, y2 + 1) for x in range(x1, x2 + 1) for z in range(z1, z2 + 1)]
    out = {}
    for start in range(0, len(positions), READ_CHUNK):
        chunk = positions[start:start + READ_CHUNK]
        blocks = ctx.server.post("/build/read-blocks", {"dimension": DIMENSION, "positions": [list(p) for p in chunk]})["blocks"]
        for pos, b in zip(chunk, blocks):
            out[pos] = b["state"]
    return out


def _intruder_overlaps(ctx, origin, width=None, depth=None, floors=None):
    """Another player submits a plan on exactly this site. Returns the status of the job it ends up with."""
    patch = plans.hut_patch(origin, "north", width=width, depth=depth, floors=floors)
    source = _write_plan("p4-intruder", patch)
    _teleport(ctx, origin[0] + NEAR_SITE, origin[1], origin[2] + NEAR_SITE, FACE_NORTH, 20)  # its chunks must be loaded
    time.sleep(SETTLE_S)
    ctx.server.post("/build/submit", {"player": INTRUDER, "source": source})
    pending, _ = ctx.server.poll("/build/pending", {"player": INTRUDER}, lambda r: r["state"] in ("OFFERED", "FAILED"),
                                 harness.JOB_TIMEOUT_S)
    if pending["state"] == "FAILED":
        return {"offer": pending, "job": None}
    approval = ctx.server.post("/build/approve", {"player": INTRUDER, "hash": pending["hash"], "confirmTerraform": True,
                                                  "confirmDestructive": True})
    if approval.get("approved") is not True:
        return {"offer": pending, "approval": approval, "job": None}
    # a fake player is never online: a job that got its claim waits PAUSED(OWNER_OFFLINE); one that did not is CANCELLED
    final, _ = ctx.server.poll("/build/status", {"jobId": approval["jobId"]},
                               lambda r: r["state"] in TERMINAL_STATES or r["state"] == "PAUSED", 60)
    return {"offer": pending, "approval": approval, "job": final}


def _overlap_reported(result):
    text = str(result)
    return OVERLAP_CODE in text


def cancel(ctx):
    job_id, manifest_hash = _start_big_build(ctx, "cancel", CANCEL_STAND)
    before = _wait_half(ctx, job_id)
    reply = ctx.server.post("/build/cancel", {"player": PLAYER, "jobId": job_id})
    ctx.save_json("cancel.json", {"before": before, "reply": reply})
    assert reply["result"] == "OK", reply
    final, _ = ctx.server.poll("/build/status", {"jobId": job_id}, lambda r: r["state"] in TERMINAL_STATES, 60)
    ctx.save_json("status.json", final)
    assert final["state"] == "CANCELLED", final
    # what was placed stays: count the planned positions that already hold their planned block
    manifest = ctx.server.post("/build/manifest", {"hash": manifest_hash})
    last = {tuple(p["pos"]): p["block"]["id"] for p in manifest["placements"]}
    positions = sorted(last)
    kept = 0
    for start in range(0, len(positions), READ_CHUNK):
        chunk = positions[start:start + READ_CHUNK]
        blocks = ctx.server.post("/build/read-blocks", {"dimension": DIMENSION, "positions": [list(p) for p in chunk]})["blocks"]
        kept += sum(1 for pos, b in zip(chunk, blocks) if b["state"].split("[")[0] == last[pos])
    ctx.save_json("kept.json", {"keptPlannedBlocks": kept, "plannedPositions": len(positions), "cursorAtCancel": before["cursor"]})
    assert kept > 0, "a cancel must not undo what was already placed"
    assert kept < len(positions), "the hut was already complete: the cancel came too late to test anything"
    # the claim is still held: another player's plan on the same site is refused
    sx, sy, sz = CANCEL_STAND
    overlap = _intruder_overlaps(ctx, (sx + BIG_WIDTH + EDGE_GAP, sy, sz))
    ctx.save_json("intruder.json", overlap)
    assert _overlap_reported(overlap), f"after a cancel the claim must still protect the site: {overlap}"
    return [ctx.out("cancel.json"), ctx.out("status.json"), ctx.out("kept.json"), ctx.out("intruder.json")]


def rollback(ctx):
    sx, sy, sz = ROLLBACK_STAND
    _prepare_view(ctx)
    _teleport(ctx, sx, sy, sz, FACE_NORTH, 20)
    time.sleep(SETTLE_S)
    before = _read_box(ctx, ROLLBACK_STAND)
    ctx.save_json("before.json", {f"{x},{y},{z}": st for (x, y, z), st in sorted(before.items())})
    pending = _submit_and_offer(ctx, {"source": "sample:hut", "here": True})
    approval = ctx.server.post("/build/approve", {"player": PLAYER, "hash": pending["hash"], "confirmTerraform": True})
    assert approval["approved"] is True, approval
    job_id = approval["jobId"]
    built = _run_job(ctx, job_id)
    assert built["state"] == "VERIFIED", built
    claim_id = built["claimId"]
    manifest = _read_back_and_compare(ctx, pending["hash"])
    wall = next(p for p in manifest["placements"] if p["block"]["id"] == "minecraft:stone_bricks" and p.get("verify") == "EXACT")
    gold_pos = tuple(wall["pos"])
    _setblock(ctx, list(gold_pos), GOLD)
    jobs_before = len(ctx.server.post("/build/jobs", {})["jobs"])

    # the preview starts nothing
    preview = ctx.server.post("/build/rollback", {"player": PLAYER, "claimId": claim_id, "confirm": False})
    ctx.save_json("preview.json", preview)
    assert "jobId" not in preview, f"a rollback without confirm must not start a job: {preview}"
    assert len(ctx.server.post("/build/jobs", {})["jobs"]) == jobs_before, "the preview made a job"

    # the confirmed rollback: the gold block (a player's) is left alone -> PARTIAL, one conflict
    started = ctx.server.post("/build/rollback", {"player": PLAYER, "claimId": claim_id, "confirm": True})
    ctx.save_json("rollback-1.json", started)
    assert started["result"] == "OK" and started.get("jobId"), started
    first = _run_job(ctx, started["jobId"])
    ctx.save_json("status-1.json", first)
    assert first["state"] == "PARTIAL" and first["conflicts"] == 1, first
    assert "conflict=1" in (first.get("lastError") or ""), first
    after = _read_box(ctx, ROLLBACK_STAND)
    differing = sorted(pos for pos in before if before[pos] != after[pos])
    ctx.save_json("after-1.json", {"differing": [list(p) for p in differing], "gold": list(gold_pos)})
    assert differing == [gold_pos], f"every block but the gold one must be back as before: {differing[:8]}"
    assert after[gold_pos].startswith(GOLD), after[gold_pos]
    x1, y1, z1, x2, y2, z2 = BEFORE_BOX
    box = [sx + x1, sy + y1, sz + z1, sx + x2, sy + y2, sz + z2]
    items = ctx.server.post("/build/entities", {"dimension": DIMENSION, "type": "minecraft:item", "box": box})
    ctx.save_json("entities.json", items)
    assert items["count"] == 0, f"the rollback dropped {items['count']} items (attached blocks must come off with their support)"

    # the claim is still held (something is owed): an overlapping plan is refused
    intruder_origin = (sx + RB_EDGE, sy, sz)
    overlap = _intruder_overlaps(ctx, intruder_origin)
    ctx.save_json("intruder-1.json", overlap)
    assert _overlap_reported(overlap), f"a claim with an owed position must still protect the site: {overlap}"

    # put the gold block's position back as it was before, roll back again: now it is clean
    _setblock(ctx, list(gold_pos), before[gold_pos])
    again = ctx.server.post("/build/rollback", {"player": PLAYER, "claimId": claim_id, "confirm": True})
    ctx.save_json("rollback-2.json", again)
    assert again["result"] == "OK" and again.get("jobId"), again
    second = _run_job(ctx, again["jobId"])
    ctx.save_json("status-2.json", second)
    assert second["state"] == "VERIFIED", second
    final = _read_box(ctx, ROLLBACK_STAND)
    still = sorted(pos for pos in before if before[pos] != final[pos])
    ctx.save_json("after-2.json", {"differing": [list(p) for p in still]})
    assert not still, f"after the second rollback the site must equal the before-picture: {still[:8]}"
    # the claim is released: the same site can be used again
    free = _intruder_overlaps(ctx, intruder_origin)
    ctx.save_json("intruder-2.json", free)
    assert not _overlap_reported(free), f"a rolled-back site must be free again: {free}"
    return [ctx.out(n) for n in ("before.json", "preview.json", "status-1.json", "after-1.json", "entities.json", "intruder-1.json",
                                 "status-2.json", "after-2.json", "intruder-2.json")] + [_screenshot(ctx, "rollback")]
