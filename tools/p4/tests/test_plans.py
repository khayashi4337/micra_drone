import unittest

from tools.p4 import plans


class PlansTest(unittest.TestCase):
    def test_hut_patch_swaps_the_site_and_keeps_the_ops(self):
        golden = plans.load_golden_patch()
        patch = plans.hut_patch((5, -60, 7), "east")
        site = plans.site_of(patch)
        self.assertEqual([5, -60, 7], site["origin"])
        self.assertEqual("east", site["facing"])
        self.assertEqual(len(golden["ops"]), len(patch["ops"]))
        self.assertEqual([100, 64, 200], plans.site_of(golden)["origin"], "the golden file itself must not change")

    def test_golden_hash_is_a_sha256(self):
        self.assertRegex(plans.golden_hash(), r"^[0-9a-f]{64}$")


if __name__ == "__main__":
    unittest.main()
