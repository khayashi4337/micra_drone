package io.github.khayashi4337.micradrone.build.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class BlockRotationTest {
    @Test
    void facingRotatesClockwiseAndUpDownStay() {
        BlockSpec stairs = BlockSpec.of("minecraft:oak_stairs", "facing", "north", "half", "bottom");
        assertEquals(BlockSpec.of("minecraft:oak_stairs", "facing", "east", "half", "bottom"), BlockRotation.rotate(stairs, 1));
        assertEquals(BlockSpec.of("minecraft:oak_stairs", "facing", "south", "half", "bottom"), BlockRotation.rotate(stairs, 2));
        BlockSpec barrel = BlockSpec.of("minecraft:barrel", "facing", "up");
        assertEquals(barrel, BlockRotation.rotate(barrel, 3));
    }

    @Test
    void axisSwapsXAndZOnOddTurnsOnly() {
        BlockSpec log = BlockSpec.of("minecraft:oak_log", "axis", "x");
        assertEquals("z", BlockRotation.rotate(log, 1).get("axis"));
        assertEquals("x", BlockRotation.rotate(log, 2).get("axis"));
        assertEquals("y", BlockRotation.rotate(BlockSpec.of("minecraft:oak_log", "axis", "y"), 1).get("axis"));
    }

    @Test
    void sideKeysAndRotationProperty() {
        BlockSpec fence = BlockSpec.of("minecraft:oak_fence", "north", "true", "east", "false");
        BlockSpec turned = BlockRotation.rotate(fence, 1);
        assertEquals("true", turned.get("east"));   // north -> east
        assertEquals("false", turned.get("south")); // east -> south
        BlockSpec sign = BlockSpec.of("minecraft:oak_sign", "rotation", "14");
        assertEquals("2", BlockRotation.rotate(sign, 1).get("rotation")); // +4 mod 16
    }

    @Test
    void zeroTurnsReturnsTheSameInstance() {
        BlockSpec s = BlockSpec.of("minecraft:oak_stairs", "facing", "north");
        assertSame(s, BlockRotation.rotate(s, 0));
        assertSame(s, BlockRotation.rotate(s, 4));
    }

    @Test
    void mirrorFlipsEastWestFacingHingeAndStairShape() {
        BlockSpec door = BlockSpec.of("minecraft:oak_door", "facing", "east", "half", "lower", "hinge", "left");
        BlockSpec m = BlockRotation.mirrorU(door);
        assertEquals("west", m.get("facing"));
        assertEquals("right", m.get("hinge"));
        BlockSpec stairs = BlockSpec.of("minecraft:oak_stairs", "facing", "north", "shape", "inner_left");
        assertEquals("inner_right", BlockRotation.mirrorU(stairs).get("shape"));
        assertEquals("north", BlockRotation.mirrorU(stairs).get("facing"));
    }

    @Test
    void transformMirrorsFirstThenRotates() {
        BlockSpec s = BlockSpec.of("minecraft:oak_stairs", "facing", "east");
        // mirror: east -> west, then one quarter turn: west -> north
        assertEquals("north", BlockRotation.transform(s, new Rot(1, true)).get("facing"));
    }
}
