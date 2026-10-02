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

    def test_a_passing_but_partial_condition_is_reported_as_partial(self):
        f = evidence.RunFolder("p", root=Path(tempfile.mkdtemp()), partial={12: "Task 27: survival"})
        f.record("a", [12, 1], evidence.PASS, "")
        self.assertEqual({1: "PASS", 12: "PARTIAL"}, f.condition_states())
        f.record("b", [12], evidence.FAIL, "boom")
        self.assertEqual("FAIL", f.condition_states()[12])

    def test_summary_json_shape(self):
        f = self.folder()
        f.record("a", [1], evidence.PASS, "", files=["x.json"])
        s = json.loads((f.path / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual("t", s["runId"])
        self.assertEqual("PASS", s["scenarios"]["a"]["status"])
        self.assertEqual({"1": "PASS"}, s["conditions"])
        self.assertTrue(f.all_passed())

    def test_a_condition_with_no_scenario_shows_as_pending_never_absent(self):
        f = evidence.RunFolder("p", root=Path(tempfile.mkdtemp()), pending={7: "Task 27"})
        f.record("a", [1], evidence.PASS, "")
        self.assertEqual({1: "PASS", 7: "PENDING"}, f.condition_states())
        s = json.loads((f.path / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual({"7": "Task 27"}, s["pendingConditions"])
        self.assertEqual("PENDING", s["conditions"]["7"])


if __name__ == "__main__":
    unittest.main()
