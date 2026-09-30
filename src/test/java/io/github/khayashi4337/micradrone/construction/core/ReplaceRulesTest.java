package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.ReplacePolicy;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.PlacerId;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReplaceRulesTest {
    private static final IntPos POS = new IntPos(0, 64, 0);
    private static final BlockSpec PLANKS = BlockSpec.of("minecraft:oak_planks");

    static Placement put(BlockSpec block, ReplacePolicy policy) {
        return put(block, policy, VerifyMode.EXACT);
    }

    static Placement put(BlockSpec block, ReplacePolicy policy, VerifyMode verify) {
        return new Placement(0, POS, block, Map.of(), "wall-n", BuildPhase.STRUCTURE, PlacerId.SIMPLE, verify,
                policy, null);
    }

    private static ReplaceDecision decide(ReplacePolicy policy, WorldCell cell) {
        return ReplaceRules.decide(put(PLANKS, policy), cell, false, Optional.empty());
    }

    @Test
    void replaceablePlacesOverAirPlantsSnowAndAsksForFluidsAndLeaves() {
        assertEquals(new ReplaceDecision.Place(Destruction.NONE), decide(ReplacePolicy.REPLACEABLE, WorldCell.of(BlockSpec.AIR)));
        assertEquals(new ReplaceDecision.Place(Destruction.NONE),
                decide(ReplacePolicy.REPLACEABLE, WorldCell.of(BlockSpec.of("minecraft:short_grass"), CellTrait.REPLACEABLE)));
        assertEquals(new ReplaceDecision.Place(Destruction.FLUID), decide(ReplacePolicy.REPLACEABLE,
                WorldCell.of(BlockSpec.of("minecraft:water"), CellTrait.REPLACEABLE, CellTrait.FLUID)));
        assertEquals(new ReplaceDecision.Place(Destruction.LEAVES),
                decide(ReplacePolicy.REPLACEABLE, WorldCell.of(BlockSpec.of("minecraft:oak_leaves"), CellTrait.LEAVES)));
        assertTrue(Destruction.FLUID.needsDestructiveConfirm());
        assertTrue(Destruction.LEAVES.needsDestructiveConfirm());
        assertTrue(Destruction.EMPTY_CONTAINER.needsDestructiveConfirm());
        assertFalse(Destruction.NONE.needsDestructiveConfirm());
        assertFalse(Destruction.TERRAIN.needsDestructiveConfirm(), "terrain has its own confirmation (terraform)");
    }

    @Test
    void solidTerrainAndUnbreakableBlocksAreRefused() {
        assertEquals(new ReplaceDecision.Refused(Refusal.NOT_REPLACEABLE),
                decide(ReplacePolicy.REPLACEABLE, WorldCell.of(BlockSpec.of("minecraft:stone"), CellTrait.TERRAFORMABLE)));
        assertEquals(new ReplaceDecision.Refused(Refusal.UNBREAKABLE),
                decide(ReplacePolicy.REPLACEABLE, WorldCell.of(BlockSpec.of("minecraft:bedrock"), CellTrait.UNBREAKABLE)));
    }

    @Test
    void foreignBlockEntitiesAreNeverReplacedExceptAnEmptyContainer() {
        WorldCell fullChest = WorldCell.withBlockEntity(BlockSpec.of("minecraft:chest"), "minecraft:chest");
        WorldCell emptyChest = WorldCell.withBlockEntity(BlockSpec.of("minecraft:chest"), "minecraft:chest",
                CellTrait.EMPTY_CONTAINER);
        assertEquals(new ReplaceDecision.Refused(Refusal.FOREIGN_BLOCK_ENTITY), decide(ReplacePolicy.REPLACEABLE, fullChest));
        assertEquals(new ReplaceDecision.Place(Destruction.EMPTY_CONTAINER), decide(ReplacePolicy.REPLACEABLE, emptyChest));
        assertEquals(new ReplaceDecision.Refused(Refusal.FOREIGN_BLOCK_ENTITY), decide(ReplacePolicy.AIR_ONLY, emptyChest),
                "air-only never replaces a container");
    }

    @Test
    void airOnlyAndExpectAreStrict() {
        assertEquals(new ReplaceDecision.Place(Destruction.NONE), decide(ReplacePolicy.AIR_ONLY, WorldCell.of(BlockSpec.AIR)));
        assertEquals(new ReplaceDecision.Refused(Refusal.NOT_REPLACEABLE),
                decide(ReplacePolicy.AIR_ONLY, WorldCell.of(BlockSpec.of("minecraft:short_grass"), CellTrait.REPLACEABLE)));
        assertEquals(new ReplaceDecision.Place(Destruction.NONE),
                decide(new ReplacePolicy.Expect("minecraft:dirt"), WorldCell.of(BlockSpec.of("minecraft:dirt"))));
        assertEquals(new ReplaceDecision.Refused(Refusal.EXPECTED_OTHER),
                decide(new ReplacePolicy.Expect("minecraft:dirt"), WorldCell.of(BlockSpec.of("minecraft:stone"))));
    }

    @Test
    void aJournaledPositionThatAlreadyHoldsTheBlockIsDoneAndOurOwnBlocksMayBeOverwritten() {
        WorldCell planks = WorldCell.of(PLANKS);
        assertEquals(new ReplaceDecision.AlreadyDone(),
                ReplaceRules.decide(put(PLANKS, ReplacePolicy.REPLACEABLE), planks, true, Optional.of(PLANKS)));
        assertEquals(new ReplaceDecision.Refused(Refusal.NOT_REPLACEABLE),
                ReplaceRules.decide(put(PLANKS, ReplacePolicy.REPLACEABLE), planks, false, Optional.empty()),
                "somebody else's planks are not ours to overwrite");
        BlockSpec north = BlockSpec.of("minecraft:oak_stairs", "facing", "north");
        WorldCell turned = WorldCell.of(BlockSpec.of("minecraft:oak_stairs", "facing", "east"));
        assertEquals(new ReplaceDecision.Place(Destruction.NONE),
                ReplaceRules.decide(put(north, ReplacePolicy.REPLACEABLE), turned, true, Optional.of(north)),
                "the project's own block may be re-placed (repair, modify), even in another state");
    }

    @Test
    void aRegisteredPositionThatNowHoldsSomethingElseIsNotOursAnyMore() {
        BlockSpec cobble = BlockSpec.of("minecraft:cobblestone");
        WorldCell gold = WorldCell.of(BlockSpec.of("minecraft:gold_block"));
        assertEquals(new ReplaceDecision.Refused(Refusal.NOT_REPLACEABLE),
                ReplaceRules.decide(put(cobble, ReplacePolicy.REPLACEABLE), gold, true, Optional.of(cobble)),
                "the registry still lists our cobblestone, but a player put gold there: never overwritten");
        assertEquals(new ReplaceDecision.Place(Destruction.NONE),
                ReplaceRules.decide(put(cobble, ReplacePolicy.REPLACEABLE), WorldCell.of(BlockSpec.AIR, CellTrait.REPLACEABLE),
                        true, Optional.of(cobble)), "a broken block of ours is air now, which anyone may fill");
    }

    @Test
    void journaledExactMismatchIsNotAlreadyDone() {
        BlockSpec stone = BlockSpec.of("minecraft:stone");
        WorldCell now = WorldCell.of(stone.with("x", "1"));
        assertEquals(new ReplaceDecision.Refused(Refusal.NOT_REPLACEABLE),
                ReplaceRules.decide(put(stone, ReplacePolicy.AIR_ONLY), now, true, Optional.empty()),
                "EXACT verification does not count an extra observed state as done");
    }

    @Test
    void journaledBlockOnlyIgnoresStateDifferences() {
        BlockSpec north = BlockSpec.of("minecraft:oak_stairs", "facing", "north");
        WorldCell now = WorldCell.of(north.with("facing", "east"));
        assertEquals(new ReplaceDecision.AlreadyDone(),
                ReplaceRules.decide(put(north, ReplacePolicy.REPLACEABLE, VerifyMode.BLOCK_ONLY), now, true,
                        Optional.empty()));
    }

    @Test
    void journaledStateSubsetStillAcceptsExtraStates() {
        BlockSpec stone = BlockSpec.of("minecraft:stone");
        WorldCell now = WorldCell.of(stone.with("x", "1"));
        assertEquals(new ReplaceDecision.AlreadyDone(),
                ReplaceRules.decide(put(stone, ReplacePolicy.AIR_ONLY, VerifyMode.STATE_SUBSET), now, true,
                        Optional.empty()), "STATE_SUBSET keeps the previous AlreadyDone behaviour");
    }

    @Test
    void expectOnWaterKeepsTheFluidClassification() {
        assertEquals(new ReplaceDecision.Place(Destruction.FLUID),
                decide(new ReplacePolicy.Expect("minecraft:water"),
                        WorldCell.of(BlockSpec.of("minecraft:water"), CellTrait.FLUID)),
                "a matching fluid is still destruction that needs confirming");
    }

    @Test
    void expectOnLeavesKeepsTheLeavesClassification() {
        assertEquals(new ReplaceDecision.Place(Destruction.LEAVES),
                decide(new ReplacePolicy.Expect("minecraft:oak_leaves"),
                        WorldCell.of(BlockSpec.of("minecraft:oak_leaves"), CellTrait.LEAVES)));
    }

    @Test
    void expectOnPlainMatchStaysNone() {
        assertEquals(new ReplaceDecision.Place(Destruction.NONE),
                decide(new ReplacePolicy.Expect("minecraft:stone"), WorldCell.of(BlockSpec.of("minecraft:stone"))));
    }

    @Test
    void anUnloadedCellIsNotDecided() {
        assertThrows(IllegalArgumentException.class, () -> ReplaceRules.decide(put(PLANKS, ReplacePolicy.REPLACEABLE),
                WorldCell.unloaded(), false, Optional.empty()));
    }
}
