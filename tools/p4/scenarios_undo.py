"""mvp-undo: a child builds a hut with the けんちく buttons and then takes it back with もとにもどす (asked once more before it
happens). Everything goes through devkit endpoints that mirror a click or a typed message.

Checks: the undo button appears only after the build; "no" at the question brings the button back and changes nothing; "yes"
undoes the hut: the whole area is exactly what it was before the build (block and block state); the child is told in Japanese; the
ordinary chat overlay behind the IDE never shows a command, a hash or a job/claim id; a new request is possible afterwards.
"""
import time

from tools.p4 import harness
from tools.p4.scenarios_basic import PLAYER, _prepare_view, _screenshot, _teleport
from tools.p4.scenarios_mvp import (CONTROLLER_OFFSET, FACE_NORTH, FLOW_STEP_TIMEOUT_S, LEAK_PATTERN, POLL_S, REQUEST, _state,
                                    _wait_flow)
from tools.p4.scenarios_rollback import _read_box

UNDO_STAND = (2600, -60, 0)
UNDO_TIMEOUT_S = 240
SETTLE_S = 2


def _press(ctx, kind):
    ctx.client.post("/build-press", {"kind": kind})


def mvp_undo(ctx):
    files = []
    _prepare_view(ctx)
    sx, sy, sz = UNDO_STAND
    _teleport(ctx, sx, sy, sz, FACE_NORTH, 20)
    time.sleep(SETTLE_S)
    cx, cy, cz = sx + CONTROLLER_OFFSET[0], sy + CONTROLLER_OFFSET[1], sz + CONTROLLER_OFFSET[2]
    ctx.server.post("/server/run-command", {"command": f"setblock {cx} {cy} {cz} micradrone:drone_controller"})
    time.sleep(1)
    before = _read_box(ctx, UNDO_STAND)
    chat_since = ctx.client.post("/chat-log", {})["next"]
    ctx.client.post("/open-ide", {"x": cx, "y": cy, "z": cz})
    ctx.client.post("/build-mode", {"enabled": True})
    ctx.client.post("/send-message", {"text": REQUEST})
    st, _ = _wait_flow(ctx, {"NEED_CONSENT", "OFFERED"}, FLOW_STEP_TIMEOUT_S)
    if st["buildFlowState"] == "NEED_CONSENT":
        _press(ctx, "CONSENT_YES")
        _wait_flow(ctx, {"OFFERED"}, FLOW_STEP_TIMEOUT_S)
    _press(ctx, "BUILD")
    st, _ = _wait_flow(ctx, {"DONE"}, UNDO_TIMEOUT_S)
    assert "UNDO" in st["buildButtons"], f"after a finished build the undo button must be offered: {st['buildButtons']}"
    changed = _read_box(ctx, UNDO_STAND)
    assert changed != before, "the build changed nothing: there is nothing to undo"
    files.append(_screenshot(ctx, "undo-1-built"))

    # the question: "no" changes nothing and brings the button back
    _press(ctx, "UNDO")
    st, _ = _wait_flow(ctx, {"CONFIRM_UNDO"}, 30)
    assert st["buildButtons"] == ["UNDO_YES", "UNDO_NO"], st["buildButtons"]
    files.append(_screenshot(ctx, "undo-2-question"))
    _press(ctx, "UNDO_NO")
    st, _ = _wait_flow(ctx, {"DONE"}, 30)
    assert "UNDO" in st["buildButtons"], st["buildButtons"]
    assert _read_box(ctx, UNDO_STAND) == changed, "answering no must change nothing"

    # "yes": the hut comes down and the world is what it was before
    _press(ctx, "UNDO")
    _wait_flow(ctx, {"CONFIRM_UNDO"}, 30)
    _press(ctx, "UNDO_YES")
    deadline = time.monotonic() + UNDO_TIMEOUT_S
    seen = []
    while True:
        st = _state(ctx)
        if not seen or seen[-1] != st["buildFlowState"]:
            seen.append(st["buildFlowState"])
        if st["buildFlowState"] == "IDLE":
            break
        if time.monotonic() >= deadline:
            ctx.save_json("stuck.json", {"state": st["buildFlowState"], "lastProgress": st.get("buildLastProgress"),
                                         "lastOffer": st.get("buildLastOffer"), "buttons": st.get("buildButtons")})
            raise AssertionError(f"the undo never finished: {seen} {st['buildTranscript'][-200:]!r}")
        time.sleep(POLL_S)
    text = st["buildTranscript"]
    ctx.save_json("flow.json", {"states": seen, "transcript": text})
    files.append(ctx.out("flow.json"))
    files.append(_screenshot(ctx, "undo-3-done"))
    assert "もとの けしきに もどったよ" in text, f"the child was not told the world is back: {text[-300:]!r}"
    assert not LEAK_PATTERN.findall(text), f"technical words in the child-visible text: {text!r}"
    after = _read_box(ctx, UNDO_STAND)
    # Vanilla, not the undo: grass under an opaque block (the drone controller this scenario placed) decays to dirt on its own
    # (measured: p4-undo-006 had exactly this one block differ). Only that block is left out of the comparison.
    decaying = (cx, cy - 1, cz)
    differing = sorted(pos for pos in before if before[pos] != after[pos] and pos != decaying)
    ctx.save_json("compare.json", {"positions": len(before), "differingCount": len(differing),
                                   "excludedByVanillaDecay": list(decaying),
                                   "differing": [{"pos": list(p), "before": before[p], "changedByBuild": changed[p],
                                                  "after": after[p]} for p in differing[:50]]})
    files.append(ctx.out("compare.json"))
    assert not differing, f"after the undo {len(differing)} blocks differ from the world before the build: {differing[:6]}"
    vanilla = ctx.client.post("/chat-log", {"since": chat_since})
    ctx.save_json("vanilla-chat.json", vanilla)
    files.append(ctx.out("vanilla-chat.json"))
    leaks = [line["text"] for line in vanilla["lines"] if LEAK_PATTERN.search(line["text"]) or "claim-" in line["text"]]
    assert not leaks, f"the ordinary chat shows technical words to a child using the buttons: {leaks[:3]}"
    return files
