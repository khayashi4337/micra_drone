package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BlockToItemTest {
    @Test
    void doorsSlabsSignsAndBeltsUseTheirItems() {
        assertEquals(Optional.of(new ItemCount("minecraft:oak_door", 1)),
                BlockToItem.cost(BlockSpec.of("minecraft:oak_door", "half", "lower")));
        assertTrue(BlockToItem.cost(BlockSpec.of("minecraft:oak_door", "half", "upper")).isEmpty(), "one item per door");
        assertEquals(Optional.of(new ItemCount("minecraft:oak_slab", 2)),
                BlockToItem.cost(BlockSpec.of("minecraft:oak_slab", "type", "double")));
        assertEquals(Optional.of(new ItemCount("minecraft:oak_slab", 1)),
                BlockToItem.cost(BlockSpec.of("minecraft:oak_slab", "type", "bottom")));
        assertEquals(Optional.of(new ItemCount("minecraft:oak_sign", 1)),
                BlockToItem.cost(BlockSpec.of("minecraft:oak_wall_sign", "facing", "north")));
        assertEquals(Optional.of(new ItemCount("create:belt_connector", 1)), BlockToItem.cost(BlockSpec.of("create:belt")));
        assertTrue(BlockToItem.cost(BlockSpec.AIR).isEmpty());
    }

    @Test
    void cutGroundYieldsWhatMiningWouldGive() {
        assertEquals(Optional.of(new ItemCount("minecraft:dirt", 1)), BlockToItem.cutYield(BlockSpec.of("minecraft:grass_block")));
        assertEquals(Optional.of(new ItemCount("minecraft:cobblestone", 1)), BlockToItem.cutYield(BlockSpec.of("minecraft:stone")));
        assertEquals(Optional.of(new ItemCount("minecraft:cobbled_deepslate", 1)),
                BlockToItem.cutYield(BlockSpec.of("minecraft:deepslate")));
        assertEquals(Optional.of(new ItemCount("minecraft:sand", 1)), BlockToItem.cutYield(BlockSpec.of("minecraft:sand")));
        assertTrue(BlockToItem.cutYield(BlockSpec.AIR).isEmpty());
    }

    @Test
    void mergeAddsUpTheSameItemsInIdOrder() {
        assertEquals(List.of(new ItemCount("a:x", 3), new ItemCount("b:y", 1)),
                BlockToItem.merge(List.of(new ItemCount("b:y", 1), new ItemCount("a:x", 1), new ItemCount("a:x", 2))));
        assertThrows(IllegalArgumentException.class, () -> new ItemCount("a:x", 0));
    }

    @Test
    void blocksWithoutAnItemCostWhatBreakingThemGivesBack() {
        assertEquals(Optional.of(new ItemCount("create:shaft", 1)), BlockToItem.cost(BlockSpec.of("create:powered_shaft")),
                "an IMPLICIT block: a steam engine turns a placed shaft into it, and it drops the shaft");
        assertEquals(Optional.of(new ItemCount("minecraft:torch", 1)), BlockToItem.cost(BlockSpec.of("minecraft:wall_torch")));
        assertEquals(Optional.of(new ItemCount("minecraft:redstone", 1)),
                BlockToItem.cost(BlockSpec.of("minecraft:redstone_wire")));
        assertEquals(Optional.of(new ItemCount("minecraft:white_banner", 1)),
                BlockToItem.cost(BlockSpec.of("minecraft:white_wall_banner")));
        assertEquals(Optional.of(new ItemCount("minecraft:oak_hanging_sign", 1)),
                BlockToItem.cost(BlockSpec.of("minecraft:oak_wall_hanging_sign")));
        assertTrue(BlockToItem.cost(BlockSpec.of("minecraft:sunflower", "half", "upper")).isEmpty());
        assertEquals(Optional.of(new ItemCount("minecraft:sunflower", 1)),
                BlockToItem.cost(BlockSpec.of("minecraft:sunflower", "half", "lower")));
        assertEquals(Optional.of(new ItemCount("minecraft:pitcher_plant", 1)),
                BlockToItem.cost(BlockSpec.of("minecraft:pitcher_plant", "half", "lower")));
        assertTrue(BlockToItem.cost(BlockSpec.of("minecraft:pitcher_plant", "half", "upper")).isEmpty(),
                "a pitcher plant is one two-block plant: its upper half drops nothing");
        assertTrue(BlockToItem.cost(BlockSpec.of("minecraft:red_bed", "part", "head")).isEmpty());
        assertEquals(Optional.of(new ItemCount("minecraft:red_bed", 1)), BlockToItem.cost(BlockSpec.of("minecraft:red_bed", "part", "foot")));
    }

    @Test
    void aPitcherPlantPairCostsOneItemInTheBillOfMaterials() {
        List<Placement> ps = List.of(
                TestManifests.put(0, 64, 0, "minecraft:pitcher_plant", "half", "lower"),
                TestManifests.put(0, 65, 0, "minecraft:pitcher_plant", "half", "upper"));
        assertEquals(Map.of("minecraft:pitcher_plant", 1), BomCalculator.bom(ps));
    }
}
