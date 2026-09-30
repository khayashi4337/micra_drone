"""Stand-in for `claude -p --output-format json` used by the automated real-game checks.

The game starts `claude` through cmd.exe /c, so a claude.cmd in front of PATH is enough. The prompt arrives on stdin (the
bridge never puts it on argv); we read it, pick a canned reply, and print the JSON object the bridge parses:
{"is_error": false, "result": "<text>", "session_id": "..."}.
The real CLI is run separately (once) by the acceptance scenario; this stub only makes the other runs deterministic.
"""
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SAMPLE = REPO / "src" / "main" / "resources" / "data" / "micradrone" / "build_samples" / "hut.json"
RED_ROOF = "minecraft:red_nether_bricks"
SESSION_ID = "stub-session"


def canned_reply(prompt):
    patch = json.loads(SAMPLE.read_text(encoding="utf-8"))
    says = "ちいさな こやを つくるよ"
    if "赤" in prompt.split("子供の依頼:")[-1]:
        says = "やねが あかい こやを つくるよ"
        for op in patch["ops"]:
            if op["op"] == "set_style":
                op["style"]["palette"]["roof"] = RED_ROOF
    return says + "\n```json\n" + json.dumps(patch, ensure_ascii=False) + "\n```\n"


def main():
    raw = sys.stdin.buffer.read().decode("utf-8", errors="replace")
    reply = {"type": "result", "is_error": False, "result": canned_reply(raw), "session_id": SESSION_ID}
    sys.stdout.write(json.dumps(reply, ensure_ascii=True))
    sys.stdout.flush()


if __name__ == "__main__":
    main()
