"""Scenarios for the after-build check (L7), terrain, safety limits and the pinned survey (conditions 2, 8, 12, 15, 16).

Each scenario stands on the flat grass at its own x offset so the huts never overlap. Positions come from the
server's own manifest (/build/manifest), never from a copy of the golden file.
"""
import json
import time

from tools.p4 import compare, harness, plans
from tools.p4.scenarios_basic import (DIMENSION, PLAYER, TERMINAL_STATES, _chat, _command, _prepare_view, _read_back_and_compare,
                                      _read_block, _run_job, _screenshot, _submit_and_offer, _teleport)

GRASS_Y = -60
REPAIR_STAND = (400, GRASS_Y, 0)
PARTIAL_STAND = (500, GRASS_Y, 0)
SLOPE_STAND = (600, GRASS_Y, 0)
LIMITS_STAND = (700, GRASS_Y, 0)
PINNED_STAND = (800, GRASS_Y, 0)
FACE_NORTH = 180
FLIP_FACING = {"north": "south", "south": "north", "east": "west", "west": "east"}
GOLD = "minecraft:gold_block"
SETTLE_S = 2


def _state_text(block, **override):
    props = dict(block.get("props") or {})
    props.update(override)
    if not props:
        return block["id"]
    return block["id"] + "[" + ",".join(f"{k}={props[k]}" for k in sorted(props)) + "]"


def _setblock(ctx, pos, state_text):
    out = _command(ctx, f"setblock {pos[0]} {pos[1]} {pos[2]} {state_text}")
    ctx.setblock_log.append({"pos": list(pos), "state": state_text, "output": out})
    return out


def _read_state(ctx, pos):
    return _read_block(ctx, pos)["state"]


def _build_here(ctx, stand, confirm_terraform=True):
    """Stand on the ground, submit sample:hut 'here', approve, wait for the job. Returns (manifest, final status)."""
    _prepare_view(ctx)
    _teleport(ctx, *stand, FACE_NORTH, 20)
    time.sleep(SETTLE_S)
    pending = _submit_and_offer(ctx, {"source": "sample:hut", "here": True})
    body = {"player": PLAYER, "hash": pending["hash"]}
    if confirm_terraform:
        body["confirmTerraform"] = True
    approval = ctx.server.post("/build/approve", body)
    assert approval["approved"] is True, approval
    final = _run_job(ctx, approval["jobId"])
    manifest = ctx.server.post("/build/manifest", {"hash": pending["hash"]})
    return pending, approval["jobId"], manifest, final


def _pick(manifest, predicate, skip=0):
    hits = [p for p in manifest["placements"] if predicate(p)]
    assert len(hits) > skip, "the manifest has no placement matching the scenario's need"
    return hits[skip]


def _verify(ctx, job_id):
    reply = ctx.server.post("/build/verify", {"player": PLAYER, "jobId": job_id})
    assert reply["result"] == "OK" and reply.get("jobId"), f"verify refused: {reply}"
    final = _run_job(ctx, reply["jobId"])
    return reply["jobId"], final


def l7_repair(ctx):
    pending, job_id, manifest, final = _build_here(ctx, REPAIR_STAND)
    assert final["state"] == "VERIFIED", final
    _read_back_and_compare(ctx, pending["hash"])
    is_wall = lambda p: p["block"]["id"] == "minecraft:stone_bricks" and p.get("verify") == "EXACT"
    wall_a = _pick(manifest, is_wall)
    wall_c = _pick(manifest, is_wall, skip=5)
    stairs = _pick(manifest, lambda p: p["block"]["id"].endswith("_stairs") and "facing" in (p["block"].get("props") or {}))
    doors = [p for p in manifest["placements"] if p["block"]["id"].endswith("_door")]
    assert len(doors) >= 2, "the hut should have a two-block door"
    # break: (a) air, (b) turn the stairs, (c) gold block, (d) open the door (both halves)
    _setblock(ctx, wall_a["pos"], "minecraft:air")
    turned = _state_text(stairs["block"], facing=FLIP_FACING[stairs["block"]["props"]["facing"]])
    _setblock(ctx, stairs["pos"], turned)
    _setblock(ctx, wall_c["pos"], GOLD)
    # a door cannot be opened one half at a time with setblock (each half copies `open` from the other), so power it:
    # a redstone block next to the door opens it the way a player-built contraption would
    door_x, door_y, door_z = doors[0]["pos"]
    _setblock(ctx, [door_x, door_y, door_z - 1], "minecraft:redstone_block")
    time.sleep(SETTLE_S)
    before_verify = {"doors": [_read_state(ctx, d["pos"]) for d in doors[:2]], "gold": _read_state(ctx, wall_c["pos"])}
    ctx.save_json("before-verify.json", before_verify)
    ctx.save_json("setblock-log.json", ctx.setblock_log)
    assert all("open=true" in st for st in before_verify["doors"]), f"the door did not stay open after setblock: {before_verify}"
    ctx.save_json("damage.json", {"air": wall_a["pos"], "turned": [stairs["pos"], turned], "gold": wall_c["pos"],
                                  "doors": [d["pos"] for d in doors[:2]]})
    repair_id, repaired = _verify(ctx, job_id)
    ctx.save_json("verify-status.json", repaired)
    assert repaired["state"] == "PARTIAL", f"a kept gold block must leave the job PARTIAL: {repaired}"
    assert repaired["conflicts"] == 1, repaired
    assert "conflict=1" in (repaired.get("lastError") or ""), repaired
    after = {
        "a": _read_state(ctx, wall_a["pos"]), "b": _read_state(ctx, stairs["pos"]),
        "c": _read_state(ctx, wall_c["pos"]), "d": [_read_state(ctx, d["pos"]) for d in doors[:2]]}
    ctx.save_json("after-repair.json", after)
    assert after["a"] == _state_text(wall_a["block"]) or after["a"].startswith(wall_a["block"]["id"]), after
    assert compare.parse_state(after["b"])[1].get("facing") == stairs["block"]["props"]["facing"], after
    assert after["c"].startswith(GOLD), f"the player's gold block must stay: {after}"
    assert all("open=true" in s for s in after["d"]), f"a door opened by use must not be closed by the repair: {after}"
    # put the original back, verify again -> VERIFIED
    _setblock(ctx, wall_c["pos"], _state_text(wall_c["block"]))
    _, again = _verify(ctx, job_id)
    ctx.save_json("verify-again-status.json", again)
    assert again["state"] == "VERIFIED", again
    return [ctx.out("damage.json"), ctx.out("verify-status.json"), ctx.out("after-repair.json"),
            ctx.out("verify-again-status.json"), _screenshot(ctx, "l7-repair"), _chat(ctx)]


def l7_partial(ctx):
    pending, job_id, manifest, final = _build_here(ctx, PARTIAL_STAND)
    assert final["state"] == "VERIFIED", final
    wall = _pick(manifest, lambda p: p["block"]["id"] == "minecraft:stone_bricks" and p.get("verify") == "EXACT")
    x, y, z = wall["pos"]
    _setblock(ctx, wall["pos"], "minecraft:air")
    ctx.server.post("/spike/protect-box", {"dimension": DIMENSION, "box": [x, y, z, x, y, z], "cancelPlace": True})
    try:
        since = ctx.client.post("/chat-log", {})["next"]
        _, repaired = _verify(ctx, job_id)
        ctx.save_json("verify-status.json", repaired)
        assert repaired["state"] == "PARTIAL", repaired
        assert "blocked=1" in (repaired.get("lastError") or ""), repaired
        time.sleep(1)
        chat = ctx.client.post("/chat-log", {"since": since})
        ctx.save_json("chat.json", chat)
        assert any("ほとんど できたけど" in line["text"] for line in chat["lines"]), chat
    finally:
        ctx.server.post("/spike/protect-box", {"dimension": DIMENSION, "box": None})
    return [ctx.out("verify-status.json"), ctx.out("chat.json"), _screenshot(ctx, "l7-partial")]


def terrain_slope(ctx):
    _prepare_view(ctx)
    sx, sy, sz = SLOPE_STAND
    _teleport(ctx, sx, sy, sz, FACE_NORTH, 20)
    time.sleep(SETTLE_S)
    # left half raised by 2 stone, right half dug 2 deep, across the hut's whole neighbourhood
    _command(ctx, f"fill {sx - 12} {sy} {sz - 20} {sx - 1} {sy + 1} {sz + 4} minecraft:stone")
    _command(ctx, f"fill {sx + 1} {sy - 2} {sz - 20} {sx + 12} {sy} {sz + 4} minecraft:air")
    time.sleep(SETTLE_S)
    pending = _submit_and_offer(ctx, {"source": "sample:hut", "here": True})
    repl = pending["replacements"]
    assert repl["terrainCut"] > 0 and repl["terrainFill"] > 0, f"the slope should need both cut and fill: {repl}"
    refused = ctx.server.post("/build/approve", {"player": PLAYER, "hash": pending["hash"]})
    ctx.save_json("approval-unconfirmed.json", refused)
    assert refused["approved"] is False and refused.get("rejection") == "TERRAFORM_UNCONFIRMED", refused
    approval = ctx.server.post("/build/approve", {"player": PLAYER, "hash": pending["hash"], "confirmTerraform": True})
    assert approval["approved"] is True, approval
    final = _run_job(ctx, approval["jobId"])
    assert final["state"] == "VERIFIED", final
    _read_back_and_compare(ctx, pending["hash"])
    return [ctx.out("pending.json"), ctx.out("compare.json"), _screenshot(ctx, "terrain-slope"), _chat(ctx)]


def _write_plan(name, patch):
    target = harness.RUN_DIR / "client" / "micradrone" / "plans" / f"{name}.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(patch), encoding="utf-8")
    return f"{name}.json"


def safety_limits(ctx):
    _prepare_view(ctx)
    lx, ly, lz = LIMITS_STAND
    _teleport(ctx, lx, ly, lz, FACE_NORTH, 20)
    time.sleep(SETTLE_S)
    # (1) a site wider than the limit: refused before anything is surveyed
    wide = plans.hut_patch((lx, ly + 1, lz + 4), "north")
    for op in wide["ops"]:
        if op["op"] == "set_site":
            op["site"]["bounds"] = [-5, -5, -5, 200, 12, 15]
    ctx.server.post("/build/submit", {"player": PLAYER, "source": _write_plan("p4-wide", wide)})
    pending, seen = ctx.server.poll("/build/pending", {"player": PLAYER},
                                    lambda r: r["state"] in ("OFFERED", "FAILED"), harness.JOB_TIMEOUT_S)
    ctx.save_json("wide-pending.json", pending)
    codes = json.dumps(pending.get("issues", []))
    assert pending["state"] == "FAILED" and "E-OUT-OF-BOUNDS" in codes, pending
    # (2) a chest with items where the hut goes: blocking issue, approval refused
    _command(ctx, f"setblock {lx} {ly + 1} {lz - 3} minecraft:chest")
    _command(ctx, f"item replace block {lx} {ly + 1} {lz - 3} container.0 with minecraft:cobblestone 5")
    pending2 = _submit_and_offer(ctx, {"source": "sample:hut", "here": True})
    ctx.save_json("chest-pending.json", pending2)
    approve2 = ctx.server.post("/build/approve", {"player": PLAYER, "hash": pending2["hash"], "confirmTerraform": True})
    ctx.save_json("chest-approval.json", approve2)
    assert approve2["approved"] is False and approve2.get("rejection") == "BLOCKING_ISSUES", approve2
    assert "E-SITE-BLOCKED" in json.dumps(approve2), approve2
    _command(ctx, f"setblock {lx} {ly + 1} {lz - 3} minecraft:air")
    # (3) water under the hut: destructive replacement needs its own confirmation
    _command(ctx, f"fill {lx - 6} {ly} {lz - 12} {lx + 6} {ly} {lz - 1} minecraft:water")
    time.sleep(SETTLE_S)
    pending3 = _submit_and_offer(ctx, {"source": "sample:hut", "here": True})
    ctx.save_json("water-pending.json", pending3)
    assert pending3["replacements"]["fluids"] > 0, pending3["replacements"]
    approve3 = ctx.server.post("/build/approve", {"player": PLAYER, "hash": pending3["hash"], "confirmTerraform": True})
    ctx.save_json("water-approval.json", approve3)
    assert approve3["approved"] is False and approve3.get("rejection") == "DESTRUCTIVE_UNCONFIRMED", approve3
    return [ctx.out("wide-pending.json"), ctx.out("chest-pending.json"), ctx.out("chest-approval.json"),
            ctx.out("water-pending.json"), ctx.out("water-approval.json"), _chat(ctx)]


def survey_pinned(ctx):
    _prepare_view(ctx)
    px, py, pz = PINNED_STAND
    _teleport(ctx, px, py, pz, FACE_NORTH, 20)
    time.sleep(SETTLE_S)
    pending = _submit_and_offer(ctx, {"source": "sample:hut", "here": True})
    # A pending approval only exposes counts, not positions; the hut's geometry relative to the standing point is
    # fixed (measured in l7-repair: a stone_bricks wall block at stand + (6, 1, 0)).
    conflict_pos = [px + 6, py + 1, pz]
    # before approving: something else is placed on a plan position, and an unrelated block changes inside the site
    _setblock(ctx, conflict_pos, "minecraft:cobblestone")
    _setblock(ctx, [px + 8, py, pz + 8], "minecraft:dirt")
    approval = ctx.server.post("/build/approve", {"player": PLAYER, "hash": pending["hash"], "confirmTerraform": True})
    ctx.save_json("approval.json", approval)
    assert approval["approved"] is True, f"the pinned hash must still be approvable: {approval}"
    paused, seen = ctx.server.poll("/build/status", {"jobId": approval["jobId"]},
                                   lambda r: r["state"] in TERMINAL_STATES or r["state"] == "PAUSED", harness.JOB_TIMEOUT_S)
    ctx.save_json("paused-status.json", paused)
    assert paused["state"] == "PAUSED" and paused.get("pause") == "SITE_CHANGED", paused
    assert paused["conflicts"] == 1, paused
    ctx.server.post("/build/resume", {"player": PLAYER, "jobId": approval["jobId"], "skipConflicts": True})
    final = _run_job(ctx, approval["jobId"])
    assert final["state"] == "PARTIAL" and "conflict=1" in (final.get("lastError") or ""), final
    _setblock(ctx, conflict_pos, "minecraft:air")
    _, again = _verify(ctx, approval["jobId"])
    ctx.save_json("verify-status.json", again)
    assert again["state"] == "VERIFIED", again
    return [ctx.out("approval.json"), ctx.out("paused-status.json"), ctx.out("verify-status.json"), _chat(ctx)]
