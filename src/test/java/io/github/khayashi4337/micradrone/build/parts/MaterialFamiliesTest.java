package io.github.khayashi4337.micradrone.build.parts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MaterialFamiliesTest {
    @Test
    void woodStoneAndBrickFamilies() {
        MaterialFamilies.Family oak = MaterialFamilies.family("minecraft:oak_planks").orElseThrow();
        assertEquals("minecraft:oak_stairs", oak.stairs());
        assertEquals("minecraft:oak_slab", oak.slab());
        MaterialFamilies.Family red = MaterialFamilies.family("minecraft:red_nether_bricks").orElseThrow();
        assertEquals("minecraft:red_nether_brick_stairs", red.stairs());
        assertEquals("minecraft:stone_brick_slab", MaterialFamilies.family("minecraft:stone_bricks").orElseThrow().slab());
    }

    @Test
    void smoothStoneHasNoStairsButHasSlab() {
        MaterialFamilies.Family f = MaterialFamilies.family("minecraft:smooth_stone").orElseThrow();
        assertNull(f.stairs());
        assertEquals("minecraft:smooth_stone_slab", f.slab());
    }

    @Test
    void terracottaAndUnknownBlocksHaveNoFamily() {
        assertTrue(MaterialFamilies.family("minecraft:red_terracotta").isEmpty(), "terracotta has no stairs in vanilla");
        assertTrue(MaterialFamilies.family("create:brass_casing").isEmpty());
    }

    @Test
    void everyEntryFollowsTheNamingShape() {
        for (String id : MaterialFamilies.fullBlockIds()) {
            MaterialFamilies.Family f = MaterialFamilies.family(id).orElseThrow();
            assertEquals(id, f.full());
            if (f.stairs() != null) {
                assertTrue(f.stairs().endsWith("_stairs"), f.stairs());
            }
            assertTrue(f.slab().endsWith("_slab"), f.slab());
            assertTrue(id.startsWith("minecraft:"));
        }
    }
}
