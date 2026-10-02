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

    def test_a_resized_hut_gets_a_site_box_that_holds_it(self):
        patch = plans.hut_patch((0, -60, 0), "north", width=40, depth=30, floors=2)
        hut = next(o["node"] for o in patch["ops"] if o["op"] == "add_node" and o["node"]["id"] == "hut")["params"]
        self.assertEqual((40, 30, 2), (hut["width"], hut["depth"], hut["floors"]))
        self.assertEqual([-5, -5, -5, 45, 2 * hut["floor_height"] + 20 + plans.ROOF_SLACK + 10, 35], plans.site_of(patch)["bounds"])
        self.assertEqual([-5, -5, -5, 15, 12, 15], plans.site_of(plans.hut_patch((0, -60, 0), "north"))["bounds"],
                         "an unresized hut keeps the golden box")

    def test_golden_hash_is_a_sha256(self):
        self.assertRegex(plans.golden_hash(), r"^[0-9a-f]{64}$")


if __name__ == "__main__":
    unittest.main()
