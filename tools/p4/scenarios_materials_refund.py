"""What a survival build gives back, and what it does not (singleplayer, the player in survival, materials in a chest in the claim).

survival-refund
  rollback  a hut paid for from a chest is rolled back: the chest holds exactly the bill of materials again (nothing more,
            nothing less), the inventory gained none of those items, and no item entity was dropped on the ground;
  cancel    a hut paid for from a chest is cancelled half way: what was consumed STAYS consumed - for every plain item the chest
            lost exactly as many as the world holds placed blocks of it - and nothing comes back afterwards.
"""
import time

from tools.p4 import harness
from tools.p4.scenarios_basic import DIMENSION, PLAYER, TERMINAL_STATES, _command
from tools.p4.scenarios_materials import (CHEST_OFFSET, FULL_HEALTH, HOLD_S, _approve, _chest_items, _fill_chest, _finished, _health,
                                          _inventory, _paused_for_materials, _start_survival_hut)

STANDS = {"rollback": (3800, -60, 0), "cancel": (4000, -60, 0)}
PLAIN_ITEMS = ("minecraft:cobblestone", "minecraft:stone_bricks", "minecraft:oak_planks", "minecraft:oak_stairs",
               "minecraft:glass_pane")  # one block = one item; a door's two halves and a lantern are left out of the cancel count
HALF = 2
READ_CHUNK = 4096
BOX_MARGIN = 20  # items dropped anywhere around the site count


def _chest_at(stand):
    return (stand[0] + CHEST_OFFSET[0], stand[1] + CHEST_OFFSET[1], stand[2] + CHEST_OFFSET[2])


def _stock_chest(ctx, stand, bom):
    chest = _chest_at(stand)
    _command(ctx, f"setblock {chest[0]} {chest[1]} {chest[2]} minecraft:chest")
    time.sleep(1)
    _fill_chest(ctx, chest, bom)
    return chest


def _bom_in(chest_items, bom):
    return {item: chest_items.get(item, 0) for item in sorted(bom)}


def _dropped_items(ctx, stand):
    x, y, z = stand
    box = [x - BOX_MARGIN, y - 5, z - BOX_MARGIN, x + BOX_MARGIN, y + 15, z + BOX_MARGIN]
    return ctx.server.post("/build/entities", {"dimension": DIMENSION, "type": "minecraft:item", "box": box})["count"]


def _placed_counts(ctx, manifest, ids):
    wanted = {tuple(p["pos"]): p["block"]["id"] for p in manifest["placements"] if p["block"]["id"] in ids}
    positions = sorted(wanted)
    placed = {item: 0 for item in ids}
    for start in range(0, len(positions), READ_CHUNK):
        chunk = positions[start:start + READ_CHUNK]
        blocks = ctx.server.post("/build/read-blocks", {"dimension": DIMENSION, "positions": [list(p) for p in chunk]})["blocks"]
        for pos, block in zip(chunk, blocks):
            if block["state"].split("[")[0] == wanted[pos]:
                placed[wanted[pos]] += 1
    return placed


def survival_refund(ctx):
    files = []
    # -- rollback: the consumed materials come back, once
    pending, bom = _start_survival_hut(ctx, STANDS["rollback"])
    items = sorted(bom)
    chest = _stock_chest(ctx, STANDS["rollback"], bom)
    job_id = _approve(ctx, pending)
    built = _finished(ctx, job_id, "rollback-build")
    assert built["state"] == "VERIFIED", built
    spent = _bom_in(_chest_items(ctx, chest), bom)
    ctx.save_json("rollback-chest-after-build.json", spent)
    assert not any(spent.values()), f"the build must have used the whole chest: {spent}"
    inventory_before = _inventory(ctx, items)
    started = ctx.server.post("/build/rollback", {"player": PLAYER, "claimId": built["claimId"], "confirm": True})
    ctx.save_json("rollback-started.json", started)
    assert started["result"] == "OK" and started.get("jobId"), started
    undone = _finished(ctx, started["jobId"], "rollback-undo")
    assert undone["state"] == "VERIFIED", undone
    back = _bom_in(_chest_items(ctx, chest), bom)
    ctx.save_json("rollback-chest-after-undo.json", back)
    assert back == {item: bom[item] for item in items}, f"the chest must hold exactly the bill of materials again: {back} vs {bom}"
    assert _inventory(ctx, items) == inventory_before, "the refund went to the inventory although the chest had room"
    assert _dropped_items(ctx, STANDS["rollback"]) == 0, "the rollback dropped items on the ground"
    files += [ctx.out("rollback-chest-after-build.json"), ctx.out("rollback-chest-after-undo.json"), ctx.out("rollback-started.json")]

    # -- cancel: what was consumed stays consumed. A hut takes about a second, so the cancel waits for a certain moment: the chest
    # holds half of every item, the job pauses for materials part way, and the cancel comes while it waits.
    pending, bom = _start_survival_hut(ctx, STANDS["cancel"])
    items = sorted(bom)
    half = {item: bom[item] // HALF for item in items if bom[item] // HALF}
    chest = _stock_chest(ctx, STANDS["cancel"], half)
    job_id = _approve(ctx, pending)
    status = _paused_for_materials(ctx, job_id)
    ctx.save_json("cancel-paused.json", status)
    assert status["state"] == "PAUSED", status
    reply = ctx.server.post("/build/cancel", {"player": PLAYER, "jobId": job_id})
    assert reply["result"] == "OK", reply
    final = _finished(ctx, job_id, "cancel")
    assert final["state"] == "CANCELLED", final
    manifest = ctx.server.post("/build/manifest", {"hash": pending["hash"]})  # a pending offer's manifest has counts only; the approved one lists placements
    left = _bom_in(_chest_items(ctx, chest), bom)
    placed = _placed_counts(ctx, manifest, set(PLAIN_ITEMS) & set(items))
    consumed = {item: half.get(item, 0) - left[item] for item in placed}
    ctx.save_json("cancel-consumed-vs-placed.json", {"chestStocked": half, "chestLeft": left, "consumed": consumed, "placed": placed})
    files.append(ctx.out("cancel-consumed-vs-placed.json"))
    assert any(consumed.values()), "nothing was consumed before the cancel: nothing to test"
    assert any(consumed[item] < bom[item] for item in consumed), "the whole bill was consumed: nothing was left unbuilt"
    assert consumed == placed, f"a cancel must neither refund nor over-take: consumed {consumed}, placed {placed}"
    time.sleep(HOLD_S)
    assert _bom_in(_chest_items(ctx, chest), bom) == left, "something came back (or went) after the cancel"
    assert _health(ctx) == FULL_HEALTH, "the player lost health"
    return files
