import unittest

from tools.p4 import compare


class CompareTest(unittest.TestCase):
    def test_parse_state(self):
        self.assertEqual(("minecraft:oak_stairs", {"facing": "north", "half": "bottom"}),
                         compare.parse_state("minecraft:oak_stairs[facing=north,half=bottom]"))
        self.assertEqual(("minecraft:stone", {}), compare.parse_state("minecraft:stone"))

    def _manifest(self, block, verify="EXACT"):
        return {"placements": [{"index": 0, "pos": [1, 64, 2], "block": block, "verify": verify}]}

    def test_listed_states_must_match_and_extra_states_are_ignored_by_state_subset(self):
        m = self._manifest({"id": "minecraft:oak_stairs", "props": {"facing": "north"}}, "STATE_SUBSET")
        ok = {(1, 64, 2): "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"}
        self.assertEqual([], compare.compare(m, ok))
        turned = {(1, 64, 2): "minecraft:oak_stairs[facing=east,half=bottom]"}
        self.assertEqual("state:facing", compare.compare(m, turned)[0].reason)

    def test_exact_compares_every_state(self):
        m = self._manifest({"id": "minecraft:oak_log", "props": {"axis": "y"}}, "EXACT")
        self.assertEqual([], compare.compare(m, {(1, 64, 2): "minecraft:oak_log[axis=y]"}))
        self.assertEqual("state:extra", compare.compare(m, {(1, 64, 2): "minecraft:oak_log[axis=y,extra=1]"})[0].reason)

    def test_block_only_compares_the_id(self):
        m = self._manifest({"id": "minecraft:glass_pane", "props": {"north": "true"}}, "BLOCK_ONLY")
        self.assertEqual([], compare.compare(m, {(1, 64, 2): "minecraft:glass_pane[north=false]"}))

    def test_missing_readback_and_wrong_block_are_reported(self):
        m = self._manifest({"id": "minecraft:stone", "props": {}})
        self.assertEqual("unread", compare.compare(m, {})[0].reason)
        self.assertEqual("block", compare.compare(m, {(1, 64, 2): "minecraft:dirt"})[0].reason)

    def test_assembled_away_is_not_compared(self):
        m = self._manifest({"id": "minecraft:white_wool", "props": {}}, "ASSEMBLED_AWAY")
        self.assertEqual([], compare.compare(m, {(1, 64, 2): "minecraft:air"}))

    def test_only_the_last_placement_at_a_position_counts(self):
        m = {"placements": [
            {"index": 0, "pos": [1, 64, 2], "block": {"id": "minecraft:dirt", "props": {}}, "verify": "EXACT"},
            {"index": 1, "pos": [1, 64, 2], "block": {"id": "minecraft:stone", "props": {}}, "verify": "EXACT"}]}
        self.assertEqual([], compare.compare(m, {(1, 64, 2): "minecraft:stone"}))


if __name__ == "__main__":
    unittest.main()
