package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import org.junit.jupiter.api.Test;

class BlockSpecTextTest {
    @Test
    void theTextFormRoundTrips() {
        for (BlockSpec b : new BlockSpec[]{BlockSpec.AIR, BlockSpec.of("minecraft:stone"),
                BlockSpec.of("minecraft:oak_stairs", "facing", "north", "half", "bottom", "shape", "straight")}) {
            assertEquals(b, BlockSpecText.parse(b.toString()));
        }
        assertThrows(IllegalArgumentException.class, () -> BlockSpecText.parse("minecraft:stone[facing]"));
        assertThrows(IllegalArgumentException.class, () -> BlockSpecText.parse(""));
    }
}
