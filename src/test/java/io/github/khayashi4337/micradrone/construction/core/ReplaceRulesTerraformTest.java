package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.compile.ReplacePolicy;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReplaceRulesTerraformTest {
    private static ReplaceDecision decide(WorldCell cell) {
        return ReplaceRules.decide(ReplaceRulesTest.put(BlockSpec.of("minecraft:cobblestone"), ReplacePolicy.TERRAFORM),
                cell, false, Optional.empty());
    }

    @Test
    void terraformTakesNaturalGroundButNotBuiltBlocks() {
        assertEquals(new ReplaceDecision.Place(Destruction.TERRAIN),
                decide(WorldCell.of(BlockSpec.of("minecraft:grass_block"), CellTrait.TERRAFORMABLE)));
        assertEquals(new ReplaceDecision.Place(Destruction.NONE), decide(WorldCell.of(BlockSpec.AIR)));
        assertEquals(new ReplaceDecision.Refused(Refusal.NOT_TERRAFORMABLE),
                decide(WorldCell.of(BlockSpec.of("minecraft:stone_bricks"))), "somebody's wall is not terrain");
        assertEquals(new ReplaceDecision.Place(Destruction.FLUID),
                decide(WorldCell.of(BlockSpec.of("minecraft:water"), CellTrait.FLUID)));
    }
}
