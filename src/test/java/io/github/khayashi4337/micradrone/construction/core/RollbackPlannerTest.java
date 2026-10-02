package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RollbackPlannerTest {
    @Test
    void everyProjectBlockIsUndoneTopDownToItsPreBuildBlock() {
        PlacedRegistry reg = new PlacedRegistry("claim-a");
        reg.apply("job-1", new JournalRecord(0, new IntPos(0, 64, 0), BlockSpec.of("minecraft:grass_block"), false,
                BlockSpec.of("minecraft:cobblestone"), 0));
        reg.apply("job-1", new JournalRecord(1, new IntPos(0, 66, 0), BlockSpec.AIR, false,
                BlockSpec.of("minecraft:oak_door", "open", "false"), 1));
        reg.apply("job-1", new JournalRecord(2, new IntPos(1, 66, 0), BlockSpec.AIR, false, BlockSpec.of("minecraft:barrel"), 2));
        List<RestoreItem> items = RollbackPlanner.plan(reg, (job, key) -> key == 1 ? Set.of("open", "powered") : Set.of());
        assertEquals(List.of(new IntPos(0, 66, 0), new IntPos(1, 66, 0), new IntPos(0, 64, 0)),
                items.stream().map(RestoreItem::pos).toList());
        assertEquals(BlockSpec.of("minecraft:grass_block"), items.get(2).restoreTo());
        assertEquals(Set.of("open", "powered"), items.get(0).volatileProps(), "an opened door still counts as ours");
        assertTrue(items.stream().allMatch(RestoreItem::dropContents));
        assertEquals("job-1", items.get(1).sourceJobId());
        assertEquals(2, items.get(1).sourceLedgerKey());
    }

    @Test
    void aDoorsHalvesGoTogetherAndASignGoesBeforeItsWall() {
        PlacedRegistry reg = new PlacedRegistry("claim-a");
        IntPos wall = new IntPos(0, 65, 0);
        IntPos sign = new IntPos(0, 65, 1);
        IntPos lower = new IntPos(2, 64, 0);
        IntPos upper = new IntPos(2, 65, 0);
        IntPos roof = new IntPos(0, 67, 0);
        reg.apply("job-1", new JournalRecord(0, wall, BlockSpec.AIR, false, BlockSpec.of("minecraft:stone_bricks"), 0));
        reg.apply("job-1", new JournalRecord(1, sign, BlockSpec.AIR, false, BlockSpec.of("minecraft:oak_wall_sign", "facing", "south"), 1));
        reg.apply("job-1", new JournalRecord(2, lower, BlockSpec.AIR, false, BlockSpec.of("minecraft:oak_door", "half", "lower"), 2));
        reg.apply("job-1", new JournalRecord(3, upper, BlockSpec.AIR, false, BlockSpec.of("minecraft:oak_door", "half", "upper"), 3));
        reg.apply("job-1", new JournalRecord(4, roof, BlockSpec.AIR, false, BlockSpec.of("minecraft:oak_planks"), 4));
        List<IntPos> order = RollbackPlanner.plan(reg, (job, key) -> Set.of()).stream().map(RestoreItem::pos).toList();
        // Attachments.REMOVAL_ORDER (Task 9): a door sorts by its lower half, so the sign one higher goes first
        assertEquals(List.of(sign, upper, lower, roof, wall), order,
                "attached blocks first (the door's halves back to back), then the rest top-down");
    }
}
