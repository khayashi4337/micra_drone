"""approve-guard (completion condition 5): the server refuses a forged manifest hash and an approval made from another
dimension, and neither refusal spoils the still-valid offer."""
import time

from tools.p4.scenarios_basic import ASIDE_OFFSET, PLAYER, _command, _prepare_view, _run_job, _submit_and_offer, _teleport

APPROVAL_STAND = (900, -60, 0)
FACE_NORTH = 180
FORGED_HASH = "0" * 64
NETHER_STAND = (0, 100, 0)
SETTLE_S = 2


def approve_guard(ctx):
    _prepare_view(ctx)
    _teleport(ctx, *APPROVAL_STAND, FACE_NORTH, 20)
    time.sleep(SETTLE_S)
    pending = _submit_and_offer(ctx, {"source": "sample:hut", "here": True})
    real = pending["hash"]
    forged = ctx.server.post("/build/approve", {"player": PLAYER, "hash": FORGED_HASH, "confirmTerraform": True})
    ctx.save_json("forged.json", forged)
    assert forged["approved"] is False and forged.get("rejection") == "HASH_MISMATCH", forged
    # the right hash, but the submitter now stands in another dimension
    _command(ctx, f"execute in minecraft:the_nether run tp {PLAYER} {NETHER_STAND[0]} {NETHER_STAND[1]} {NETHER_STAND[2]}")
    time.sleep(SETTLE_S)
    elsewhere = ctx.server.post("/build/approve", {"player": PLAYER, "hash": real, "confirmTerraform": True})
    ctx.save_json("other-dimension.json", elsewhere)
    assert elsewhere["approved"] is False and elsewhere.get("rejection") == "DIMENSION_MISMATCH", elsewhere
    # back home the same offer is still good: the refusals did not consume or spoil it
    # back home, beside the site (its origin is the stand; a player standing on it would hold the build up)
    _command(ctx, f"execute in minecraft:overworld run tp {PLAYER} {APPROVAL_STAND[0] + ASIDE_OFFSET[0]} {APPROVAL_STAND[1]} "
                  f"{APPROVAL_STAND[2] + ASIDE_OFFSET[2]}")
    time.sleep(SETTLE_S)
    approval = ctx.server.post("/build/approve", {"player": PLAYER, "hash": real, "confirmTerraform": True})
    ctx.save_json("approval.json", approval)
    assert approval["approved"] is True, approval
    final = _run_job(ctx, approval["jobId"])
    assert final["state"] == "VERIFIED", final
    return [ctx.out("forged.json"), ctx.out("other-dimension.json"), ctx.out("approval.json"), ctx.out("status.json")]
