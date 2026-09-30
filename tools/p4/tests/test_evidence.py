import json
import tempfile
import unittest
from pathlib import Path

from tools.p4 import evidence


class EvidenceTest(unittest.TestCase):
    def folder(self):
        return evidence.RunFolder("t", root=Path(tempfile.mkdtemp()))

    def test_not_run_and_fail_need_a_reason(self):
        f = self.folder()
        with self.assertRaises(ValueError):
            f.record("a", [1], evidence.NOT_RUN, "")
        with self.assertRaises(ValueError):
            f.record("a", [1], evidence.FAIL, None)
        f.record("a", [1], evidence.PASS, "")

    def test_condition_state_rules(self):
        f = self.folder()
        f.record("a", [1, 2], evidence.PASS, "")
        f.record("b", [2], evidence.NOT_RUN, "eula")
        f.record("c", [3], evidence.FAIL, "boom")
        f.record("d", [3], evidence.NOT_RUN, "x")
        self.assertEqual({1: "PASS", 2: "NOT-RUN", 3: "FAIL"}, f.condition_states())
        self.assertFalse(f.all_passed())

    def test_summary_json_shape(self):
        f = self.folder()
        f.record("a", [1], evidence.PASS, "", files=["x.json"])
        s = json.loads((f.path / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual("t", s["runId"])
        self.assertEqual("PASS", s["scenarios"]["a"]["status"])
        self.assertEqual({"1": "PASS"}, s["conditions"])
        self.assertTrue(f.all_passed())


if __name__ == "__main__":
    unittest.main()
