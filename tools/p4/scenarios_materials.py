"""Survival materials in the real game (singleplayer; the player is in survival so the job consumes).

survival-materials  The child's inventory is never spent without consent:
                    1. a hut is approved in survival while the inventory holds the WHOLE bill of materials and no chest exists:
                       the job pauses MATERIALS_MISSING and the inventory is exactly as it was (a decoy that must stay);
                    2. a chest inside the claim holds half of the bill: still paused, inventory still untouched;
                    3. the chest gets the rest: the job resumes by itself and ends VERIFIED; the chest lost exactly the bill of
                       materials (nothing more, nothing less), the inventory is still untouched;
                    4. a second hut with no chest: the owner allows the inventory (the adult command a panel button calls), the
                       job resumes by itself, ends VERIFIED and the inventory lost exactly the bill of materials.
"""
import re
import time

from tools.p4 import harness
from tools.p4.scenarios_basic import DIMENSION, PLAYER, _command, _prepare_view, _screenshot, _submit_and_offer, _teleport
from tools.p4.scenarios_restart import FACE_NORTH

SETTLE_S = 2
HOLD_S = 8  # how long a paused job must stay paused and leave the inventory alone
CHEST_OFFSET = (-3, 0, 0)  # inside the claim's operating box (it reaches 5 blocks beyond the walls) and off every placement
STANDS = {"chest": (2800, -60, 0), "inventory": (3000, -60, 0)}
PAUSE_MATERIALS = "MATERIALS_MISSING"
POLL_S = 0.5
# the game prints in its own language (ja: "...条件に一致する49個のアイテムを持っています"): the count is the only number in the line
FOUND = re.compile(r"(\d+)")
STACK = re.compile(r"\{[^{}]*\}")
ITEM_ID = re.compile(r'id:\s*"([^"]+)"')
ITEM_COUNT = re.compile(r"[Cc]ount:\s*(\d+)")


def _inventory_count(ctx, item):
    """How many of `item` the player holds (`clear <player> <item> 0` only counts)."""
    text = " ".join(_command(ctx, f"clear {PLAYER} {item} 0"))
    found = FOUND.search(text)
    return int(found.group(1)) if found else 0


def _inventory(ctx, items):
    return {item: _inventory_count(ctx, item) for item in items}


def _chest_items(ctx, pos):
    """{item: count} of the container at `pos` (`data get block` prints the Items list; an empty one prints an error line)."""
    text = " ".join(_command(ctx, f"data get block {pos[0]} {pos[1]} {pos[2]} Items"))
    out = {}
    for stack in STACK.findall(text):
        item, count = ITEM_ID.search(stack), ITEM_COUNT.search(stack)
        if item and count:
            out[item.group(1)] = out.get(item.group(1), 0) + int(count.group(1))
    return out


def _give(ctx, bom):
    for item, count in bom.items():
        _command(ctx, f"give {PLAYER} {item} {count}")


def _fill_chest(ctx, pos, bom):
    for item, count in bom.items():
        ctx.server.post("/build/fill-container", {"dimension": DIMENSION, "pos": list(pos), "item": item, "count": count})


def _paused_for_materials(ctx, job_id):
    status, _ = ctx.server.poll("/build/status", {"jobId": job_id},
                                lambda r: r["state"] == "PAUSED" and r.get("pause") == PAUSE_MATERIALS, harness.JOB_TIMEOUT_S)
    return status


def _stays_paused(ctx, job_id, seconds):
    deadline = time.monotonic() + seconds
    status = None
    while time.monotonic() < deadline:
        status = ctx.server.post("/build/status", {"jobId": job_id})
        assert status["state"] == "PAUSED" and status.get("pause") == PAUSE_MATERIALS, f"left the pause by itself: {status}"
        time.sleep(POLL_S)
    return status


def _finished(ctx, job_id, name):
    final, seen = ctx.server.poll("/build/status", {"jobId": job_id}, lambda r: r["state"] in ("VERIFIED", "PARTIAL", "FAILED", "CANCELLED"),
                                  harness.JOB_TIMEOUT_S)
    ctx.save_json(f"{name}-timeline.json", seen)
    ctx.save_json(f"{name}-status.json", final)
    return final


def _start_survival_hut(ctx, stand):
    sx, sy, sz = stand
    _prepare_view(ctx)
    _command(ctx, f"clear {PLAYER}")
    _teleport(ctx, sx, sy, sz, FACE_NORTH, 20)
    time.sleep(SETTLE_S)
    _command(ctx, f"gamemode survival {PLAYER}")
    pending = _submit_and_offer(ctx, {"source": "sample:hut", "here": True})
    manifest = ctx.server.post("/build/manifest", {"hash": pending["hash"]})
    bom = manifest["bom"]
    assert bom, f"the manifest carries no bill of materials: {manifest.keys()}"
    return pending, bom


def _approve(ctx, pending):
    approval = ctx.server.post("/build/approve", {"player": PLAYER, "hash": pending["hash"], "confirmTerraform": True})
    assert approval["approved"] is True, approval
    return approval["jobId"]


def survival_materials(ctx):
    files = []
    # -- 1 + 2 + 3: the chest path; the inventory holds a decoy that must stay untouched
    pending, bom = _start_survival_hut(ctx, STANDS["chest"])
    items = sorted(bom)
    _give(ctx, bom)
    decoy = _inventory(ctx, items)
    assert decoy == {item: bom[item] for item in items}, f"the decoy inventory is not the bill of materials: {decoy}"
    job_id = _approve(ctx, pending)
    paused = _paused_for_materials(ctx, job_id)
    ctx.save_json("paused-1.json", paused)
    _stays_paused(ctx, job_id, HOLD_S)
    assert _inventory(ctx, items) == decoy, "the inventory was spent without the owner's consent"

    sx, sy, sz = STANDS["chest"]
    chest = (sx + CHEST_OFFSET[0], sy + CHEST_OFFSET[1], sz + CHEST_OFFSET[2])
    _teleport(ctx, sx, sy, sz, FACE_NORTH, 20)
    _command(ctx, f"setblock {chest[0]} {chest[1]} {chest[2]} minecraft:chest")
    time.sleep(1)
    half = {item: bom[item] // 2 for item in items[:len(items) // 2 + 1]}
    _fill_chest(ctx, chest, half)
    ctx.save_json("chest-half.json", _chest_items(ctx, chest))
    _stays_paused(ctx, job_id, HOLD_S)
    assert _inventory(ctx, items) == decoy, "a half-full chest made the job reach into the inventory"

    # the rest goes in: the job must resume BY ITSELF
    _fill_chest(ctx, chest, {item: bom[item] - half.get(item, 0) for item in items})
    final = _finished(ctx, job_id, "chest")
    assert final["state"] == "VERIFIED", final
    chest_after = _chest_items(ctx, chest)
    ctx.save_json("chest-after.json", chest_after)
    # terrain the job cut (dirt) may have been handed to the chest: only the bill of materials' items are counted
    leftover = {item: n for item, n in chest_after.items() if item in bom and n}
    assert not leftover, f"the chest must have lost exactly the bill of materials: {chest_after}"
    assert _inventory(ctx, items) == decoy, "the inventory changed while the chest paid for the build"
    files.append(_screenshot(ctx, "materials-chest"))

    # -- 4: no chest; the owner allows the inventory
    pending, bom = _start_survival_hut(ctx, STANDS["inventory"])
    items = sorted(bom)
    _give(ctx, bom)
    job_id = _approve(ctx, pending)
    paused = _paused_for_materials(ctx, job_id)
    ctx.save_json("paused-2.json", paused)
    _stays_paused(ctx, job_id, HOLD_S)
    assert _inventory(ctx, items) == {item: bom[item] for item in items}, "the inventory was spent without the owner's consent"
    claim_id = paused["claimId"]
    ctx.server.post("/server/run-as", {"player": PLAYER, "command": f"micradrone build supply inventory {claim_id} on"})
    final = _finished(ctx, job_id, "inventory")
    assert final["state"] == "VERIFIED", final
    left = _inventory(ctx, items)
    ctx.save_json("inventory-after.json", left)
    assert not any(left.values()), f"the inventory must have lost exactly the bill of materials: {left}"
    files.append(_screenshot(ctx, "materials-inventory"))
    return files + [ctx.out(n) for n in ("paused-1.json", "chest-half.json", "chest-after.json", "paused-2.json", "inventory-after.json",
                                         "chest-status.json", "inventory-status.json")]
