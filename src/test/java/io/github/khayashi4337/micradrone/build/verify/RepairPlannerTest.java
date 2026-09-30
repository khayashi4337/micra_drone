package io.github.khayashi4337.micradrone.build.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.compile.ConflictKind;
import io.github.khayashi4337.micradrone.build.compile.ObservedBlock;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class RepairPlannerTest {
    private final PlacementManifest m = TestManifests.smallHut();

    private Deviation dev(int index, BlockSpec observed, DeviationKind kind) {
        return new Deviation(index, m.placements().get(index).block(), new ObservedBlock(observed), kind);
    }

    @Test
    void missingBlocksAndOurOwnTurnedBlocksAreReplaced() {
        RepairPlan p = RepairPlanner.plan(m, List.of(dev(0, BlockSpec.AIR, DeviationKind.MISSING),
                dev(10, BlockSpec.of("minecraft:oak_planks", "x", "y"), DeviationKind.WRONG_STATE)),
                i -> Optional.of(BlockSpec.AIR), pos -> true, Set.of());
        assertEquals(List.of(0, 10), p.reapply());
        assertEquals(List.of(), p.conflicts());
    }

    @Test
    void aReplacedBlockIsAConflictNotARepair() {
        RepairPlan p = RepairPlanner.plan(m, List.of(dev(12, BlockSpec.of("minecraft:gold_block"), DeviationKind.WRONG_BLOCK)),
                i -> Optional.of(BlockSpec.AIR), pos -> true, Set.of());
        assertEquals(List.of(), p.reapply(), "never overwrite what a player may have put there");
        assertEquals(1, p.conflicts().size());
        assertEquals(ConflictKind.PLAYER_MODIFIED, p.conflicts().get(0).kind());
        assertEquals(m.placements().get(12).pos(), p.conflicts().get(0).pos());
    }

    @Test
    void theStillUntouchedPreBuildBlockIsJustNotPlacedYet() {
        RepairPlan p = RepairPlanner.plan(m, List.of(dev(12, BlockSpec.of("minecraft:short_grass"), DeviationKind.WRONG_BLOCK)),
                i -> Optional.of(BlockSpec.of("minecraft:short_grass")), pos -> false, Set.of());
        assertEquals(List.of(12), p.reapply());
    }

    @Test
    void aTurnedBlockWeDidNotPlaceIsAConflict() {
        RepairPlan p = RepairPlanner.plan(m, List.of(dev(10, BlockSpec.of("minecraft:oak_planks"), DeviationKind.WRONG_STATE)),
                i -> Optional.empty(), pos -> false, Set.of());
        assertEquals(List.of(), p.reapply());
        assertEquals(1, p.conflicts().size());
    }

    @Test
    void protectedPositionsCannotBeFixedHere() {
        RepairPlan p = RepairPlanner.plan(m, List.of(dev(3, BlockSpec.AIR, DeviationKind.MISSING)),
                i -> Optional.empty(), pos -> false, Set.of(3));
        assertEquals(List.of(), p.reapply());
        assertEquals(List.of(DeviationKind.BLOCKED), p.unfixable().stream().map(Deviation::kind).toList());
    }

    @Test
    void aWrongStateRepairKeepsTheVolatileStateTheWorldShows() {
        IntPos doorPos = new IntPos(0, 64, 0);
        BlockSpec expected = BlockSpec.of("minecraft:oak_door", "facing", "north", "open", "false");
        PlacementManifest door = TestManifests.of(new Box(-1, 60, -1, 1, 70, 1), List.of(
                TestManifests.put(doorPos, expected, "door-1", BuildPhase.ENVELOPE, VerifyMode.STATE_SUBSET)));
        BlockSpec observed = BlockSpec.of("minecraft:oak_door", "facing", "east", "open", "true");
        Function<String, Set<String>> doorVolatile = node -> node.equals("door-1") ? Set.of("open", "powered")
                : Set.of();
        RepairPlan p = RepairPlanner.plan(door, List.of(new Deviation(0, expected, new ObservedBlock(observed),
                        DeviationKind.WRONG_STATE)), i -> Optional.empty(), pos -> true, Set.of(), doorVolatile);
        assertEquals(List.of(0), p.reapply());
        // repair puts the expected non-volatile states back but keeps the volatile ones the world shows (07 P4 条件15)
        assertEquals(BlockSpec.of("minecraft:oak_door", "facing", "north", "open", "true"),
                p.reapplyBlocks().get(0), "the door stays open; only the wrong facing is put back");
    }

    @Test
    void aVolatileStateMissingFromTheWorldKeepsTheExpectedOne() {
        IntPos doorPos = new IntPos(0, 64, 0);
        BlockSpec expected = BlockSpec.of("minecraft:oak_door", "facing", "north", "open", "false");
        PlacementManifest door = TestManifests.of(new Box(-1, 60, -1, 1, 70, 1), List.of(
                TestManifests.put(doorPos, expected, "door-1", BuildPhase.ENVELOPE, VerifyMode.STATE_SUBSET)));
        BlockSpec observed = BlockSpec.of("minecraft:oak_door", "facing", "east");
        RepairPlan p = RepairPlanner.plan(door, List.of(new Deviation(0, expected, new ObservedBlock(observed),
                        DeviationKind.WRONG_STATE)), i -> Optional.empty(), pos -> true, Set.of(),
                node -> node.equals("door-1") ? Set.of("open", "powered") : Set.of());
        assertEquals(List.of(0), p.reapply());
        assertEquals(expected, p.reapplyBlocks().get(0), "nothing observed to keep: the expected block goes in");
    }

    @Test
    void thePreBuildBlockPutBackWhereWePlacedIsAPlayersConflictNotARepair() {
        RepairPlan p = RepairPlanner.plan(m, List.of(dev(12, BlockSpec.of("minecraft:short_grass"), DeviationKind.WRONG_BLOCK)),
                i -> Optional.of(BlockSpec.of("minecraft:short_grass")), pos -> true, Set.of());
        assertEquals(List.of(), p.reapply(), "our block was placed here, so the grass is somebody's doing");
        assertEquals(1, p.conflicts().size());
    }
}
