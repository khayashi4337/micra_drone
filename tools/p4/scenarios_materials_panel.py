"""The child's way through the けんちく panel when the chests do not hold enough materials (survival, stub AI).

mvp-materials-panel
  no   the question "もちものからも つかう？" appears ONCE; "つかわない" changes nothing (the inventory stays whole, the job stays
       paused, the question is not asked again); then materials put in a chest next to the site finish the build by themselves
       without touching the inventory;
  yes  the question appears; "つかう" lets the build use the inventory and the build finishes with the inventory used up;
  in both the child's text carries no command, id or hash, and the player is never hurt.
"""
import time

from tools.p4 import harness
from tools.p4.scenarios_basic import PLAYER, _command, _prepare_view, _screenshot, _step_aside, _teleport
from tools.p4.scenarios_materials import (CHEST_OFFSET, FULL_HEALTH, HOLD_S, POLL_S, SETTLE_S, _fill_chest, _give, _health,
                                          _inventory)
from tools.p4.scenarios_mvp import CONTROLLER_OFFSET, FLOW_STEP_TIMEOUT_S, LEAK_PATTERN, REQUEST, _state, _wait_flow
from tools.p4.scenarios_restart import FACE_NORTH

PANEL_STANDS = {"no": (3400, -60, 0), "yes": (3600, -60, 0)}
ASK_TEXT = "きみの もちものからも つかう?"  # the child-facing question when the chests alone are not enough
SOURCE_TEXT = "ばしょの チェストから つかうよ"  # the offer's line naming where the materials come from
INVENTORY_ON_TEXT = "きみの もちものからも つかうね"
INVENTORY_OFF_TEXT = "きみの もちものは つかわないね"
QUESTION_TIMEOUT_S = 120


def _panel_offer(ctx, stand):
    """A survival child opens the けんちく panel at `stand`, asks for a hut and gets the offer. Returns the bill of materials."""
    sx, sy, sz = stand
    _prepare_view(ctx)
    _command(ctx, f"clear {PLAYER}")
    _teleport(ctx, sx, sy, sz, FACE_NORTH, 20)
    time.sleep(SETTLE_S)
    cx, cy, cz = sx + CONTROLLER_OFFSET[0], sy + CONTROLLER_OFFSET[1], sz + CONTROLLER_OFFSET[2]
    _command(ctx, f"setblock {cx} {cy} {cz} micradrone:drone_controller")
    time.sleep(1)
    _command(ctx, f"gamemode survival {PLAYER}")
    ctx.client.post("/open-ide", {"x": cx, "y": cy, "z": cz})
    ctx.client.post("/build-mode", {"enabled": True})
    ctx.client.post("/send-message", {"text": REQUEST})
    st, _ = _wait_flow(ctx, {"NEED_CONSENT", "OFFERED"}, FLOW_STEP_TIMEOUT_S)
    if st["buildFlowState"] == "NEED_CONSENT":
        ctx.client.post("/build-press", {"kind": "CONSENT_YES"})
        st, _ = _wait_flow(ctx, {"OFFERED"}, FLOW_STEP_TIMEOUT_S)
    assert SOURCE_TEXT in st["buildTranscript"], f"the offer never said where the materials come from: {st['buildTranscript']!r}"
    pending = ctx.server.post("/build/pending", {"player": PLAYER})
    bom = ctx.server.post("/build/manifest", {"hash": pending["hash"]})["bom"]
    assert bom, "the manifest carries no bill of materials"
    return bom


def _wait_question(ctx):
    deadline = time.monotonic() + QUESTION_TIMEOUT_S
    while True:
        st = _state(ctx)
        if "INVENTORY_YES" in st["buildButtons"]:
            return st
        assert time.monotonic() < deadline, f"the panel never asked about the inventory: {st['buildTranscript']!r}"
        time.sleep(POLL_S)


def mvp_materials_panel(ctx):
    files = []
    # -- no: the inventory is the child's, and the child said so
    bom = _panel_offer(ctx, PANEL_STANDS["no"])
    items = sorted(bom)
    _give(ctx, bom)
    decoy = _inventory(ctx, items)
    _step_aside(ctx)
    ctx.client.post("/build-press", {"kind": "BUILD"})
    st = _wait_question(ctx)
    assert st["buildButtons"] == ["INVENTORY_YES", "INVENTORY_NO"], st["buildButtons"]
    files.append(_screenshot(ctx, "panel-question"))
    ctx.client.post("/build-press", {"kind": "INVENTORY_NO"})
    time.sleep(HOLD_S)
    st = _state(ctx)
    asked = st["buildTranscript"].count(ASK_TEXT)
    ctx.save_json("panel-no.json", {"buttons": st["buildButtons"], "asked": asked, "transcript": st["buildTranscript"]})
    files.append(ctx.out("panel-no.json"))
    assert INVENTORY_OFF_TEXT in st["buildTranscript"], st["buildTranscript"]
    assert "INVENTORY_YES" not in st["buildButtons"], "the question came back after the child said no"
    assert asked == 1, f"the question was asked {asked} times"
    assert _inventory(ctx, items) == decoy, "the inventory was spent after the child said no"
    sx, sy, sz = PANEL_STANDS["no"]
    chest = (sx + CHEST_OFFSET[0], sy + CHEST_OFFSET[1], sz + CHEST_OFFSET[2])
    _command(ctx, f"setblock {chest[0]} {chest[1]} {chest[2]} minecraft:chest")
    time.sleep(1)
    _fill_chest(ctx, chest, bom)
    st, _ = _wait_flow(ctx, {"DONE"}, harness.JOB_TIMEOUT_S)
    assert _inventory(ctx, items) == decoy, "the chest paid for the build but the inventory changed"
    assert not LEAK_PATTERN.findall(st["buildTranscript"]), f"technical words in the child's text: {st['buildTranscript']!r}"
    files.append(_screenshot(ctx, "panel-no-done"))

    # -- yes: the child lets the build use the inventory
    bom = _panel_offer(ctx, PANEL_STANDS["yes"])
    items = sorted(bom)
    _give(ctx, bom)
    _step_aside(ctx)
    ctx.client.post("/build-press", {"kind": "BUILD"})
    _wait_question(ctx)
    ctx.client.post("/build-press", {"kind": "INVENTORY_YES"})
    st, _ = _wait_flow(ctx, {"DONE"}, harness.JOB_TIMEOUT_S)
    text = st["buildTranscript"]
    asked = text.count(ASK_TEXT)
    ctx.save_json("panel-yes.json", {"asked": asked, "transcript": text})
    files.append(ctx.out("panel-yes.json"))
    assert INVENTORY_ON_TEXT in text, text
    assert asked == 1, f"the question was asked {asked} times"
    left = _inventory(ctx, items)
    ctx.save_json("panel-yes-inventory.json", left)
    assert not any(left.values()), f"the inventory must have been used up by the build: {left}"
    assert not LEAK_PATTERN.findall(text), f"technical words in the child's text: {text!r}"
    assert _health(ctx) == FULL_HEALTH, "the player lost health"
    files.append(_screenshot(ctx, "panel-yes-done"))
    return files
