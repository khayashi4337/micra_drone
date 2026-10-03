"""When the AI cannot answer, the child is told what to do and is never left with buttons that lead nowhere (stub AI, singleplayer).

mvp-ai-not-working
  login   the AI says "Not logged in" (what the real CLI prints): the child is told, in Japanese and without technical words, to
          ask a grown-up; no buttons stay on screen; nothing is submitted;
  boom    an ordinary failure twice in a row: the first time "try again", the second time "ask a grown-up" (saying the same thing
          again would send the child round in circles); the failures of the login kind do not count in that row;
  back    once the AI answers again the next request is offered as usual (a success ends the row of failures).
"""
import time

from tools.p4.scenarios_basic import PLAYER, _prepare_view, _screenshot, _teleport
from tools.p4.scenarios_mvp import (CONTROLLER_OFFSET, FACE_NORTH, FLOW_STEP_TIMEOUT_S, LEAK_PATTERN, POLL_S, REQUEST, _state,
                                    _wait_flow)
from tools.p4.stub_mode import stub_mode

STAND = (4400, -60, 0)  # away from every other scenario's site: nothing is built here
PROBE_WAIT_S = 4  # the panel probes `claude --version` when it opens; the first request waits for the answer
LOGIN_TEXT = "ログインが まだ"
RETRY_TEXT = "もういちど いってみてね"
ADULT_TEXT = "おとなの ひとに みてもらってね"
CONSENT_TEXT = "おくるよ"  # the consent question's words: it must not come back after a failure


def _send(ctx, text):
    ctx.client.post("/send-message", {"text": text})
    st, _ = _wait_flow(ctx, {"NEED_CONSENT", "FAILED", "OFFERED"}, FLOW_STEP_TIMEOUT_S)
    if st["buildFlowState"] == "NEED_CONSENT":
        ctx.client.post("/build-press", {"kind": "CONSENT_YES"})
        st, _ = _wait_flow(ctx, {"FAILED", "OFFERED"}, FLOW_STEP_TIMEOUT_S)
    return st


def _settle(ctx):
    """The flow's last button list is what the child sees; give the screen a moment, then read it."""
    time.sleep(POLL_S)
    return _state(ctx)


def mvp_ai_not_working(ctx):
    files = []
    _prepare_view(ctx)
    sx, sy, sz = STAND
    _teleport(ctx, sx, sy, sz, FACE_NORTH, 20)
    time.sleep(2)
    cx, cy, cz = sx + CONTROLLER_OFFSET[0], sy + CONTROLLER_OFFSET[1], sz + CONTROLLER_OFFSET[2]
    ctx.server.post("/server/run-command", {"command": f"setblock {cx} {cy} {cz} micradrone:drone_controller"})
    time.sleep(1)
    ctx.client.post("/open-ide", {"x": cx, "y": cy, "z": cz})
    ctx.client.post("/build-mode", {"enabled": True})
    time.sleep(PROBE_WAIT_S)
    # an earlier scenario may have left this player's last submission behind: "nothing was submitted" means "unchanged", not "none"
    pending_before = ctx.server.post("/build/pending", {"player": PLAYER})

    with stub_mode("login"):
        st = _send(ctx, REQUEST)
        st = _settle(ctx)
        assert st["buildFlowState"] == "FAILED", st["buildFlowState"]
        assert LOGIN_TEXT in st["buildTranscript"], f"the child was not told the AI is not signed in: {st['buildTranscript']!r}"
        assert st["buildButtons"] == [], f"buttons stayed on screen after the failure: {st['buildButtons']}"
        assert not LEAK_PATTERN.findall(st["buildTranscript"]), f"technical words in the child's text: {st['buildTranscript']!r}"
        assert "login" not in st["buildTranscript"].lower() or "ログイン" in st["buildTranscript"], st["buildTranscript"]
        files.append(_screenshot(ctx, "ai-login-missing"))
        ctx.save_json("login.json", {"transcript": st["buildTranscript"], "buttons": st["buildButtons"]})
        files.append(ctx.out("login.json"))
        pending_after = ctx.server.post("/build/pending", {"player": PLAYER})
        assert (pending_after.get("state"), pending_after.get("hash")) == (pending_before.get("state"), pending_before.get("hash")),             f"something was submitted without an AI: {pending_before} -> {pending_after}"

    with stub_mode("boom"):
        before = _state(ctx)["buildTranscript"]
        first = _send(ctx, REQUEST)
        first = _settle(ctx)
        added = first["buildTranscript"][len(before):] if first["buildTranscript"].startswith(before) else first["buildTranscript"]
        assert first["buildFlowState"] == "FAILED" and RETRY_TEXT in added and ADULT_TEXT not in added, added
        assert first["buildButtons"] == [], first["buildButtons"]
        mid = first["buildTranscript"]
        second = _send(ctx, REQUEST)
        second = _settle(ctx)
        added2 = second["buildTranscript"][len(mid):] if second["buildTranscript"].startswith(mid) else second["buildTranscript"]
        assert second["buildFlowState"] == "FAILED" and ADULT_TEXT in added2, added2
        assert second["buildButtons"] == [], second["buildButtons"]
        ctx.save_json("boom.json", {"first": added, "second": added2})
        files.append(ctx.out("boom.json"))
        files.append(_screenshot(ctx, "ai-failing-twice"))

    # the AI answers again: the request is offered as usual and the row of failures ends
    back = _send(ctx, REQUEST)
    assert back["buildFlowState"] == "OFFERED", back["buildFlowState"]
    assert back["buildButtons"] == ["BUILD", "CANCEL"], back["buildButtons"]
    ctx.client.post("/build-press", {"kind": "CANCEL"})
    _wait_flow(ctx, {"IDLE", "DONE", "FAILED"}, FLOW_STEP_TIMEOUT_S)
    return files
