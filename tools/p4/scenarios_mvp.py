"""mvp-japanese-hut: the MVP acceptance run. A child types Japanese in the IDE chat's けんちく mode; the AI (a canned stub or
the real Claude CLI, chosen by `--claude`) makes a plan; the server offers it; the child answers the consent question and
presses つくる; the hut is built and every block is read back and compared with the server's manifest.

Only devkit endpoints that mirror a click or a typed message are used; no OS input reaches the game window.
"""
import re
import time

from tools.p4 import devkit_client, harness
from tools.p4.scenarios_basic import PLAYER, _prepare_view, _read_back_and_compare, _screenshot, _teleport

REQUEST = "屋根が赤い小屋を建てて"
MVP_STAND = (1400, -60, 0)
REAL_STAND = (1500, -60, 0)  # the real-CLI run builds elsewhere: a second hut on the stub run's site is (rightly) refused
OTHER_REQUEST = "おおきな小屋を建てて"  # no 赤: the stub answers with an oak roof, which cannot replace the red one
CONTROLLER_OFFSET = (0, 0, 9)  # south of the standing point; the hut is built to the north
FACE_NORTH = 180
FLOW_STEP_TIMEOUT_S = 240  # the real CLI's own timeout is 120 s per call, and a repair may add one more call
BUILD_TIMEOUT_S = 300
BLOCKED_TIMEOUT_S = 60  # a refused approval is answered at once; anything slower is a stuck panel
POLL_S = 0.5
REDDISH = ("red_nether_brick", "red_sandstone", "red_terracotta", "red_wool", "red_concrete", "minecraft:bricks", "brick_")
# what a child must never read: error codes, hashes, internal ids, command names
LEAK_PATTERN = re.compile(r"E-[A-Z]{2,}|[0-9a-f]{32,}|micradrone[:.]|/micradrone|approve|confirm-|job-\d+", re.I)
CONSENT_FILE = harness.RUN_DIR / "client" / "micradrone" / "build_consent.txt"


STATE_RETRIES = 5


def _state(ctx):
    """GET /state; the render thread may be busy for a moment (a big job, a world save): ask again a few times before failing."""
    for attempt in range(STATE_RETRIES):
        try:
            return ctx.client.get("/state")
        except devkit_client.DevkitError as e:
            if "render thread did not respond" not in str(e) or attempt == STATE_RETRIES - 1:
                raise
            time.sleep(POLL_S)


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
            try:
                ctx.save_json("stuck-jobs.json", ctx.server.post("/build/jobs", {}))
            except Exception as unreachable:  # the evidence is best effort, the failure below is what matters
                ctx.save_json("stuck-jobs.json", {"error": str(unreachable)})
            raise AssertionError(f"flow stuck at {name} (seen {seen}), waiting for {wanted}: {st.get('buildTranscript')!r}")
        time.sleep(POLL_S)


def mvp_japanese_hut(ctx):
    files = []
    if CONSENT_FILE.exists():
        CONSENT_FILE.unlink()  # the first request must ask for consent, like a child's first time
    _prepare_view(ctx)
    chat_since = ctx.client.post("/chat-log", {})["next"]
    sx, sy, sz = REAL_STAND if ctx.game.claude_mode == harness.CLAUDE_REAL else MVP_STAND
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
    # the game's ordinary chat overlay shows through behind the IDE: it must not carry commands, hashes or job ids either
    vanilla = ctx.client.post("/chat-log", {"since": chat_since})
    ctx.save_json("vanilla-chat.json", vanilla)
    files.append(ctx.out("vanilla-chat.json"))
    vanilla_leaks = [(line["text"], LEAK_PATTERN.findall(line["text"])) for line in vanilla["lines"]
                     if LEAK_PATTERN.search(line["text"])]
    assert not vanilla_leaks, f"the vanilla chat leaks technical words to a child who used the buttons: {vanilla_leaks[:3]}"

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


def mvp_cli_missing(ctx):
    """No `claude` on the game's PATH (harness mode `none`): after the consent the panel must say, in Japanese and without
    technical words, that the AI is not ready, and nothing must be built."""
    if CONSENT_FILE.exists():
        CONSENT_FILE.unlink()
    _prepare_view(ctx)
    sx, sy, sz = MVP_STAND
    _teleport(ctx, sx + 40, sy, sz, FACE_NORTH, 20)
    time.sleep(2)
    cx, cy, cz = sx + 40, sy, sz + CONTROLLER_OFFSET[2]
    ctx.server.post("/server/run-command", {"command": f"setblock {cx} {cy} {cz} micradrone:drone_controller"})
    time.sleep(1)
    ctx.client.post("/open-ide", {"x": cx, "y": cy, "z": cz})
    ctx.client.post("/build-mode", {"enabled": True})
    ctx.client.post("/send-message", {"text": REQUEST})
    _wait_flow(ctx, {"NEED_CONSENT"}, FLOW_STEP_TIMEOUT_S)
    ctx.client.post("/build-press", {"kind": "CONSENT_YES"})
    deadline = time.monotonic() + FLOW_STEP_TIMEOUT_S
    while True:
        st = _state(ctx)
        if st["buildFlowState"] == "FAILED":
            break
        assert time.monotonic() < deadline, f"the flow never failed: {st['buildFlowState']}"
        time.sleep(POLL_S)
    text = st["buildTranscript"]
    ctx.save_json("flow.json", {"state": st["buildFlowState"], "transcript": text})
    files = [ctx.out("flow.json"), _screenshot(ctx, "mvp-cli-missing")]
    assert "じゅんびが まだ" in text, f"the child was not told, in Japanese, that the AI is not ready: {text!r}"
    assert not LEAK_PATTERN.findall(text), f"technical words in the child-visible text: {text!r}"
    pending = ctx.server.post("/build/pending", {"player": PLAYER})
    assert pending["state"] == "NONE", f"nothing may be submitted when the AI is missing: {pending}"
    return files


def mvp_site_blocked(ctx):
    """A second, different hut on the site of the first one: the server refuses the approval (another building stands
    there). The panel must not stay at "building" forever: it must say, in Japanese, that it could not be built."""
    _prepare_view(ctx)
    sx, sy, sz = MVP_STAND
    _teleport(ctx, sx, sy, sz, FACE_NORTH, 20)
    time.sleep(2)
    cx, cy, cz = sx + CONTROLLER_OFFSET[0], sy + CONTROLLER_OFFSET[1], sz + CONTROLLER_OFFSET[2]
    ctx.server.post("/server/run-command", {"command": f"setblock {cx} {cy} {cz} micradrone:drone_controller"})
    time.sleep(1)
    ctx.client.post("/open-ide", {"x": cx, "y": cy, "z": cz})
    ctx.client.post("/build-mode", {"enabled": True})
    chat_since = ctx.client.post("/chat-log", {})["next"]
    ctx.client.post("/send-message", {"text": OTHER_REQUEST})
    st, _ = _wait_flow(ctx, {"NEED_CONSENT", "OFFERED"}, FLOW_STEP_TIMEOUT_S)
    if st["buildFlowState"] == "NEED_CONSENT":
        ctx.client.post("/build-press", {"kind": "CONSENT_YES"})
        _wait_flow(ctx, {"OFFERED"}, FLOW_STEP_TIMEOUT_S)
    ctx.client.post("/build-press", {"kind": "BUILD"})
    deadline = time.monotonic() + BLOCKED_TIMEOUT_S
    while True:
        st = _state(ctx)
        if st["buildFlowState"] in ("FAILED", "DONE"):
            break
        assert time.monotonic() < deadline, f"the panel is stuck at {st['buildFlowState']}: {st['buildTranscript']!r}"
        time.sleep(POLL_S)
    text = st["buildTranscript"]
    ctx.save_json("flow.json", {"state": st["buildFlowState"], "transcript": text})
    files = [ctx.out("flow.json"), _screenshot(ctx, "mvp-site-blocked")]
    assert st["buildFlowState"] == "FAILED", f"the refused approval should end FAILED: {st['buildFlowState']}"
    assert "つくれなかった" in text, f"the child was not told it could not be built: {text!r}"
    assert not LEAK_PATTERN.findall(text), f"technical words in the child-visible text: {text!r}"
    jobs = ctx.server.post("/build/jobs", {})["jobs"]
    ctx.save_json("jobs.json", jobs)
    files.append(ctx.out("jobs.json"))
    vanilla = ctx.client.post("/chat-log", {"since": chat_since})
    ctx.save_json("vanilla-chat.json", vanilla)
    files.append(ctx.out("vanilla-chat.json"))
    leaks = [line["text"] for line in vanilla["lines"] if LEAK_PATTERN.search(line["text"])]
    assert not leaks, f"the refusal leaks technical words into the vanilla chat: {leaks[:3]}"
    return files
