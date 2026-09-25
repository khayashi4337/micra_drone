package io.github.khayashi4337.micradrone.build.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BlockSpecTest {
    @Test
    void propertiesAreSortedAndEqualityIgnoresInsertionOrder() {
        BlockSpec a = BlockSpec.of("minecraft:oak_stairs", "half", "bottom", "facing", "north");
        BlockSpec b = BlockSpec.of("minecraft:oak_stairs", "facing", "north", "half", "bottom");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom]", a.toString());
    }

    @Test
    void withReturnsACopy() {
        BlockSpec a = BlockSpec.of("minecraft:stone");
        BlockSpec b = a.with("k", "v");
        assertEquals(0, a.properties().size());
        assertEquals("v", b.get("k"));
        assertNotEquals(a, b);
        assertEquals("minecraft:stone", a.toString());
    }

    @Test
    void oddKeyValueCountIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BlockSpec.of("minecraft:stone", "only-key"));
    }

    @Test
    void propertiesAreImmutable() {
        BlockSpec a = BlockSpec.of("minecraft:stone", "k", "v");
        assertThrows(UnsupportedOperationException.class, () -> a.properties().put("x", "y"));
        assertEquals("minecraft:air", BlockSpec.AIR.blockId());
    }
}
