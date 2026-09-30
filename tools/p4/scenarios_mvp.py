"""mvp-japanese-hut: the MVP acceptance run. A child types Japanese in the IDE chat's けんちく mode; the AI (a canned stub or
the real Claude CLI, chosen by `--claude`) makes a plan; the server offers it; the child answers the consent question and
presses つくる; the hut is built and every block is read back and compared with the server's manifest.

Only devkit endpoints that mirror a click or a typed message are used; no OS input reaches the game window.
"""
import re
import time

from tools.p4 import harness
from tools.p4.scenarios_basic import PLAYER, _prepare_view, _read_back_and_compare, _screenshot, _teleport

REQUEST = "屋根が赤い小屋を建てて"
MVP_STAND = (1400, -60, 0)
CONTROLLER_OFFSET = (0, 0, 9)  # south of the standing point; the hut is built to the north
FACE_NORTH = 180
FLOW_STEP_TIMEOUT_S = 240  # the real CLI's own timeout is 120 s per call, and a repair may add one more call
BUILD_TIMEOUT_S = 300
POLL_S = 0.5
REDDISH = ("red_nether_brick", "red_sandstone", "red_terracotta", "red_wool", "red_concrete", "minecraft:bricks", "brick_")
# what a child must never read: error codes, hashes, internal ids, command names
LEAK_PATTERN = re.compile(r"E-[A-Z]{2,}|[0-9a-f]{32,}|micradrone[:.]|/micradrone|approve|confirm-|job-\d+", re.I)
CONSENT_FILE = harness.RUN_DIR / "client" / "micradrone" / "build_consent.txt"


def _state(ctx):
    return ctx.client.get("/state")


def _wait_flow(ctx, wanted, timeout):
    deadline = time.monotonic() + timeout
    seen = []
    while True:
        st = _state(ctx)
        name = st.get("buildFlowState")
        if not seen or seen[-1] != name:
            seen.append(name)
        if name in wanted:
            return st, seen
        if name == "FAILED":
            raise AssertionError(f"the flow failed while waiting for {wanted}: {st.get('buildTranscript')!r}")
        if time.monotonic() > deadline:
            raise AssertionError(f"flow stuck at {name} (seen {seen}), waiting for {wanted}: {st.get('buildTranscript')!r}")
        time.sleep(POLL_S)


def mvp_japanese_hut(ctx):
    files = []
    if CONSENT_FILE.exists():
        CONSENT_FILE.unlink()  # the first request must ask for consent, like a child's first time
    _prepare_view(ctx)
    sx, sy, sz = MVP_STAND
    _teleport(ctx, sx, sy, sz, FACE_NORTH, 20)
    time.sleep(2)
    cx, cy, cz = sx + CONTROLLER_OFFSET[0], sy + CONTROLLER_OFFSET[1], sz + CONTROLLER_OFFSET[2]
    ctx.server.post("/server/run-command", {"command": f"setblock {cx} {cy} {cz} micradrone:drone_controller"})
    time.sleep(1)
    ctx.client.post("/open-ide", {"x": cx, "y": cy, "z": cz})
    ctx.client.post("/build-mode", {"enabled": True})
    assert _state(ctx)["buildMode"] is True

    ctx.client.post("/send-message", {"text": REQUEST})
    st, seen = _wait_flow(ctx, {"NEED_CONSENT"}, FLOW_STEP_TIMEOUT_S)
    assert st["buildButtons"] == ["CONSENT_YES", "CONSENT_NO"], st["buildButtons"]
    files.append(_screenshot(ctx, "mvp-consent"))
    ctx.client.post("/build-press", {"kind": "CONSENT_YES"})

    st, seen2 = _wait_flow(ctx, {"OFFERED"}, FLOW_STEP_TIMEOUT_S)
    assert st["buildButtons"] == ["BUILD", "CANCEL"], st["buildButtons"]
    offer_text = st["buildTranscript"]
    ctx.save_json("flow-before-build.json", {"claude": ctx.game.claude_mode, "states": seen + seen2, "transcript": offer_text,
                                             "buttons": st["buildButtons"]})
    files.append(ctx.out("flow-before-build.json"))
    files.append(_screenshot(ctx, "mvp-offer"))
    pending = ctx.server.post("/build/pending", {"player": PLAYER})
    ctx.save_json("pending.json", pending)
    files.append(ctx.out("pending.json"))
    assert pending["state"] == "OFFERED", pending

    ctx.client.post("/build-press", {"kind": "BUILD"})
    st, seen3 = _wait_flow(ctx, {"DONE"}, BUILD_TIMEOUT_S)
    final_text = st["buildTranscript"]
    ctx.save_json("flow-after-build.json", {"states": seen3, "transcript": final_text})
    files.append(ctx.out("flow-after-build.json"))
    files.append(_screenshot(ctx, "mvp-done"))

    # what the child could read must carry no code, hash, internal id or command name
    leaks = LEAK_PATTERN.findall(final_text)
    assert not leaks, f"child-visible text leaks technical words {leaks}: {final_text!r}"
    assert "できあがり" in final_text, f"the child was never told it is done: {final_text!r}"

    # the server side: the job finished VERIFIED and every placement reads back as planned
    jobs = ctx.server.post("/build/jobs", {})["jobs"]
    mine = [j for j in jobs if j.get("kind") == "BUILD"]
    assert mine, jobs
    latest = mine[-1]
    status = ctx.server.post("/build/status", {"jobId": latest["jobId"]})
    ctx.save_json("status.json", status)
    files.append(ctx.out("status.json"))
    assert status["state"] == "VERIFIED", status
    manifest = _read_back_and_compare(ctx, pending["hash"])
    files.append(ctx.out("compare.json"))
    ids = {p["block"]["id"] for p in manifest["placements"]}
    reddish = sorted(i for i in ids if any(m in i for m in REDDISH))
    ctx.save_json("roof-materials.json", {"reddish": reddish, "allIds": sorted(ids)})
    files.append(ctx.out("roof-materials.json"))
    assert reddish, f"the plan for 「{REQUEST}」 contains no reddish block: {sorted(ids)}"
    return files
