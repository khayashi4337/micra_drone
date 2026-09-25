package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.MaterialFamilies;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlaceableBlockPolicyTest {
    private final PlaceableBlockPolicy policy = PlaceableBlockPolicy.builtin();

    @Test
    void theAlwaysForbiddenBlocksAreRefusedEvenIfListed() {
        for (String id : PlaceableBlockPolicy.ALWAYS_FORBIDDEN) {
            assertTrue(policy.isAlwaysForbidden(id), id);
            assertTrue(policy.checkMaterial(id).isPresent(), id);
            assertTrue(new PlaceableBlockPolicy(Set.of(id)).checkMaterial(id).isPresent(), "listing must not override: " + id);
        }
        for (String id : List.of("minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
                "minecraft:bedrock", "minecraft:spawner", "minecraft:barrier", "minecraft:structure_block", "minecraft:jigsaw",
                "minecraft:light")) {
            assertTrue(PlaceableBlockPolicy.ALWAYS_FORBIDDEN.contains(id), id);
        }
    }

    @Test
    void theBuiltinListCoversTheDefaultPaletteAndTheFamilies() {
        for (String id : BuildingParts.DEFAULT_PALETTE.values()) {
            assertTrue(policy.checkMaterial(id).isEmpty(), id);
        }
        for (String full : MaterialFamilies.fullBlockIds()) {
            MaterialFamilies.Family f = MaterialFamilies.family(full).orElseThrow();
            assertTrue(policy.checkMaterial(f.full()).isEmpty(), f.full());
            if (f.stairs() != null) {
                assertTrue(policy.checkMaterial(f.stairs()).isEmpty(), f.stairs());
            }
            assertTrue(policy.checkMaterial(f.slab()).isEmpty(), f.slab());
        }
        assertTrue(policy.checkMaterial("minecraft:red_terracotta").isEmpty());
        assertTrue(policy.checkMaterial("minecraft:red_concrete").isEmpty());
        assertTrue(policy.checkMaterial("minecraft:oak_wall_sign").isEmpty());
    }

    @Test
    void unlistedBlocksAreRefusedWithAReason() {
        assertTrue(policy.checkMaterial("minecraft:tnt").orElseThrow().contains("許可"));
        assertFalse(policy.checkMaterial("minecraft:stone").isPresent());
    }
}
