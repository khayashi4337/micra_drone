import unittest

from tools.p4 import p4_scenarios

ALL_CONDITIONS = set(range(1, 17))  # design 07, P4 completion conditions 1..16


class RegistryTest(unittest.TestCase):
    def covered(self):
        out = set()
        for s in p4_scenarios.SCENARIOS.values():
            out.update(s.conditions)
        return out

    def test_every_completion_condition_has_an_automated_check_or_is_pending_with_its_task(self):
        pending = set(p4_scenarios.PENDING_CONDITIONS)
        self.assertEqual(ALL_CONDITIONS, self.covered() | pending, "P4 completion conditions 1..16 (design 07)")
        for condition, task in p4_scenarios.PENDING_CONDITIONS.items():
            self.assertTrue(task.startswith("Task "), (condition, task))

    def test_owner_only_scenarios_always_say_why(self):
        for name, s in p4_scenarios.SCENARIOS.items():
            if s.owner_only_reason is not None:
                self.assertTrue(len(s.owner_only_reason) > 20, name)


if __name__ == "__main__":
    unittest.main()
