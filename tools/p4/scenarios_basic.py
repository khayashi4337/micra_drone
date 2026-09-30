"""The first automated scenarios: devkit-smoke, hut-golden, hut-here, bad-source (all singleplayer).

A scenario is a function ctx -> list of evidence file names. It raises AssertionError (or any exception) to FAIL.
Every scenario writes into its own sub-folder of the evidence folder, named after the scenario.
"""
import time
from dataclasses import dataclass
from pathlib import Path

from tools.p4 import compare, harness, plans

PLAYER = "Dev"
DIMENSION = "minecraft:overworld"
TERMINAL_STATES = {"VERIFIED", "PARTIAL", "FAILED", "CANCELLED", "ROLLED_BACK"}
HUT_ORIGIN = (100, 64, 200)
HUT_VIEW_POS = (100, 66, 186)
HUT_VIEW_SCAFFOLD = (100, 65, 186)
HUT_HERE_STAND = (0, -60, 0)
SCAFFOLD_TIMEOUT_S = 30
SETTLE_TIMEOUT_S = 30
TERRAIN_CUT_EXPECTED = 49
BAD_SOURCE_KEY = "micradrone.build.submit.bad_source"
BAD_SOURCE_TEXT = "よめないよ"
BAD_SOURCE_PATH = "../../server.properties"


@dataclass
class Ctx:
    name: str
    folder: object  # evidence.RunFolder
    game: object  # harness.Game
    client: object  # Devkit (client API)
    server: object  # Devkit (server API)

    def out(self, file):
        return f"{self.name}/{file}"

    def save_json(self, file, obj):
        self.folder.write_json(self.out(file), obj)
        return self.out(file)


def _command(ctx, command):
    return ctx.server.post("/server/run-command", {"command": command}).get("output", [])


def _player(ctx):
    for p in ctx.server.post("/server/state")["players"]:
        if p["name"] == PLAYER:
            return p
    raise AssertionError(f"{PLAYER} is not in the world: {ctx.server.post('/server/state')['players']}")


def _teleport(ctx, x, y, z, yaw, pitch):
    _command(ctx, f"tp {PLAYER} {x} {y} {z} {yaw} {pitch}")


def _read_block(ctx, pos):
    blocks = ctx.server.post("/build/read-blocks", {"dimension": DIMENSION, "positions": [list(pos)]})["blocks"]
    return blocks[0]


def _prepare_view(ctx):
    _command(ctx, f"gamemode creative {PLAYER}")
    _command(ctx, "time set day")
    _command(ctx, "gamerule doDaylightCycle false")


def _place_scaffold(ctx, pos, block="minecraft:glass"):
    """setblock only works in a loaded chunk, so retry until the block reads back."""
    deadline = time.monotonic() + SCAFFOLD_TIMEOUT_S
    while True:
        _command(ctx, f"setblock {pos[0]} {pos[1]} {pos[2]} {block}")
        read = _read_block(ctx, pos)
        if read.get("loaded") and read["state"].startswith(block):
            return
        if time.monotonic() > deadline:
            raise AssertionError(f"scaffold {block} at {pos} never appeared; last read {read}")
        time.sleep(harness.POLL_INTERVAL_S)


def _submit_and_offer(ctx, body):
    ctx.server.post("/build/submit", {"player": PLAYER, **body})
    pending, seen = ctx.server.poll("/build/pending", {"player": PLAYER},
                                    lambda r: r["state"] in ("OFFERED", "FAILED"), harness.JOB_TIMEOUT_S)
    ctx.save_json("pending.json", pending)
    ctx.save_json("pending-timeline.json", seen)
    assert pending["state"] == "OFFERED", f"submit did not reach OFFERED: {pending}"
    return pending


def _run_job(ctx, job_id):
    final, seen = ctx.server.poll("/build/status", {"jobId": job_id}, lambda r: r["state"] in TERMINAL_STATES,
                                  harness.JOB_TIMEOUT_S)
    ctx.save_json("timeline.json", seen)
    ctx.save_json("status.json", final)
    return final


def _read_back_and_compare(ctx, manifest_hash):
    manifest = ctx.server.post("/build/manifest", {"hash": manifest_hash})
    ctx.save_json("manifest.json", manifest)
    positions = sorted({tuple(p["pos"]) for p in manifest["placements"]})
    readback = {}
    for start in range(0, len(positions), 4096):
        chunk = positions[start:start + 4096]
        reply = ctx.server.post("/build/read-blocks",
                                {"dimension": manifest.get("dimension", DIMENSION), "positions": [list(p) for p in chunk]})
        for block in reply["blocks"]:
            readback[tuple(block["pos"])] = block["state"]
    mismatches = compare.compare(manifest, readback)
    ctx.save_json("compare.json", {"placements": len(manifest["placements"]), "positions": len(positions),
                                   "mismatchCount": len(mismatches),
                                   "mismatches": [m.__dict__ for m in mismatches[:200]]})
    assert not mismatches, f"{len(mismatches)} mismatches, first: {mismatches[0]}"
    return manifest


def _screenshot(ctx, name):
    reply = ctx.client.post("/screenshot", {"name": f"{name}.png"})
    src = Path(reply["path"])
    ctx.folder.add_file(src, ctx.out(f"{name}.png"))
    return ctx.out(f"{name}.png")


def _chat(ctx, file="chat.json"):
    return ctx.save_json(file, ctx.client.post("/chat-log", {}))


def devkit_smoke(ctx):
    files = []
    for side, api, calls in (
            ("client", ctx.client, [("GET", "/state", None), ("POST", "/chat-log", {})]),
            ("server", ctx.server, [("POST", "/server/state", {}), ("POST", "/server/save-all", {}),
                                    ("POST", "/build/jobs", {}), ("POST", "/build/pending", {"player": PLAYER}),
                                    ("POST", "/spike/probe-log", {}), ("POST", "/spike/protect-log", {})])):
        for method, path, body in calls:
            reply = api.get(path) if method == "GET" else api.post(path, body)
            for key in ("runId", "pid", "gameDir"):
                assert key in reply, f"{path} answer lacks {key}: {reply}"
            files.append(ctx.save_json(f"{side}{path.replace('/', '_')}.json", reply))
    return files


def hut_golden(ctx):
    _prepare_view(ctx)
    _teleport(ctx, HUT_VIEW_POS[0], 80, HUT_VIEW_POS[2], 0, 15)
    _place_scaffold(ctx, HUT_VIEW_SCAFFOLD)
    _teleport(ctx, *HUT_VIEW_POS, 0, 15)
    deadline = time.monotonic() + SETTLE_TIMEOUT_S
    while _player(ctx)["pos"][1] < HUT_VIEW_POS[1] - 0.5 and time.monotonic() < deadline:
        time.sleep(0.5)
    standing = _player(ctx)
    ctx.save_json("player.json", standing)
    assert abs(standing["pos"][1] - HUT_VIEW_POS[1]) < 1.0, f"not standing on the scaffold: {standing}"
    pending = _submit_and_offer(ctx, {"source": "sample:hut"})
    assert pending["hash"] == plans.golden_hash(), f"server hash {pending['hash']} != golden {plans.golden_hash()}"
    approval = ctx.server.post("/build/approve", {"player": PLAYER, "hash": pending["hash"]})
    ctx.save_json("approval.json", approval)
    assert approval["approved"] is True, approval
    final = _run_job(ctx, approval["jobId"])
    assert final["state"] == "VERIFIED", f"job ended {final['state']}: {final}"
    manifest = _read_back_and_compare(ctx, pending["hash"])
    assert len(manifest["placements"]) == plans.HUT_PLACEMENT_COUNT, len(manifest["placements"])
    return [ctx.out("pending.json"), ctx.out("timeline.json"), ctx.out("compare.json"),
            _screenshot(ctx, "hut-golden"), _chat(ctx)]


def hut_here(ctx):
    _prepare_view(ctx)
    _teleport(ctx, HUT_HERE_STAND[0], HUT_HERE_STAND[1], HUT_HERE_STAND[2], 180, 20)
    time.sleep(2)
    pending = _submit_and_offer(ctx, {"source": "sample:hut", "here": True})
    cut = pending["replacements"]["terrainCut"]
    assert cut > 0, f"the foundation should sink into the grass layer, terrainCut={cut}"
    assert cut == TERRAIN_CUT_EXPECTED, f"terrainCut {cut} != {TERRAIN_CUT_EXPECTED}"
    refused = ctx.server.post("/build/approve", {"player": PLAYER, "hash": pending["hash"]})
    ctx.save_json("approval-unconfirmed.json", refused)
    assert refused["approved"] is False and refused.get("rejection") == "TERRAFORM_UNCONFIRMED", refused
    approval = ctx.server.post("/build/approve", {"player": PLAYER, "hash": pending["hash"], "confirmTerraform": True})
    ctx.save_json("approval.json", approval)
    assert approval["approved"] is True, approval
    final = _run_job(ctx, approval["jobId"])
    assert final["state"] == "VERIFIED", f"job ended {final['state']}: {final}"
    _read_back_and_compare(ctx, pending["hash"])
    files = [ctx.out("pending.json"), ctx.out("compare.json"), _screenshot(ctx, "hut-here"), _chat(ctx)]
    latest = harness.RUN_DIR / "client" / "logs" / "latest.log"
    text = latest.read_text(encoding="utf-8", errors="replace")
    empty_tag_lines = [line for line in text.splitlines() if "BuildTags" in line and "empty" in line.lower()]
    ctx.save_json("buildtags-warnings.json", empty_tag_lines)
    assert not empty_tag_lines, f"BuildTags warned about empty tags: {empty_tag_lines[:3]}"
    return files


def bad_source(ctx):
    """The refusal goes to the player's chat (ServerMessages.send), not to the command source, so the child-visible
    place is the chat log; the command output is saved too but is expected to be empty."""
    before = ctx.server.post("/build/pending", {"player": PLAYER})
    since = ctx.client.post("/chat-log", {})["next"]
    reply = ctx.server.post("/server/run-as", {"player": PLAYER,
                                               "command": f"micradrone build submit {BAD_SOURCE_PATH}"})
    ctx.save_json("run-as.json", reply)
    deadline = time.monotonic() + SETTLE_TIMEOUT_S
    while True:
        chat = ctx.client.post("/chat-log", {"since": since})
        chat_text = "\n".join(line["text"] for line in chat["lines"])
        if BAD_SOURCE_TEXT in chat_text or time.monotonic() > deadline:
            break
        time.sleep(harness.POLL_INTERVAL_S)
    ctx.save_json("chat.json", chat)
    assert BAD_SOURCE_TEXT in chat_text, f"chat lacks the Japanese refusal: {chat_text!r}"
    assert BAD_SOURCE_KEY not in chat_text, f"the raw translation key leaked to the child: {chat_text!r}"
    after = ctx.server.post("/build/pending", {"player": PLAYER})
    ctx.save_json("pending-before-after.json", {"before": before, "after": after})
    ignore = ("pid", "runId", "gameDir")
    assert {k: v for k, v in before.items() if k not in ignore} == {k: v for k, v in after.items() if k not in ignore},         "pending changed"
    return [ctx.out("run-as.json"), ctx.out("chat.json")]
