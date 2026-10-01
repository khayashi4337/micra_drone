import json
import re
import subprocess
import sys
import unittest
from pathlib import Path

STUB = Path(__file__).resolve().parents[1] / "stub_bin" / "stub_claude.py"
RED_ROOF = "minecraft:red_nether_bricks"


def run_stub(prompt):
    out = subprocess.run([sys.executable, str(STUB), "-p", "--output-format", "json"], input=prompt.encode("utf-8"),
                         capture_output=True, timeout=30).stdout.decode("utf-8")
    return json.loads(out)


def plan_of(reply):
    return json.loads(re.search(r"```json\n(.*)\n```", reply["result"], re.S).group(1))


def roof_of(plan):
    return [o["style"]["palette"]["roof"] for o in plan["ops"] if o["op"] == "set_style"][0]


class StubClaudeTest(unittest.TestCase):
    def test_reply_has_the_shape_the_bridge_parses(self):
        reply = run_stub("子供の依頼:\n屋根が赤い小屋を建てて")
        self.assertIs(reply["is_error"], False)
        self.assertTrue(reply["session_id"])
        self.assertIn("```json", reply["result"])

    def test_a_red_request_gets_a_red_roof_and_others_keep_the_sample(self):
        self.assertEqual(RED_ROOF, roof_of(plan_of(run_stub("子供の依頼:\n屋根が赤い小屋を建てて"))))
        self.assertNotEqual(RED_ROOF, roof_of(plan_of(run_stub("子供の依頼:\nおおきな小屋を建てて"))))

    def test_the_word_red_in_the_instructions_does_not_count(self):
        # only the text after the request marker decides, the instruction part may mention any colour
        self.assertNotEqual(RED_ROOF, roof_of(plan_of(run_stub("見本の説明に赤という字がある\n子供の依頼:\nこやを建てて"))))


if __name__ == "__main__":
    unittest.main()
