package io.github.khayashi4337.micradrone.build.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class FacingTest {
    @Test
    void rotatesClockwiseSeenFromAbove() {
        assertEquals(Facing.EAST, Facing.NORTH.rotate(1));
        assertEquals(Facing.SOUTH, Facing.NORTH.rotate(2));
        assertEquals(Facing.WEST, Facing.NORTH.rotate(3));
        assertEquals(Facing.NORTH, Facing.WEST.rotate(1));
        assertEquals(Facing.WEST, Facing.NORTH.rotate(-1));
        assertEquals(Facing.NORTH, Facing.NORTH.rotate(4));
    }

    @Test
    void oppositeAndSteps() {
        assertEquals(Facing.SOUTH, Facing.NORTH.opposite());
        assertEquals(Facing.WEST, Facing.EAST.opposite());
        // local frame: NORTH = +w, EAST = +u
        assertEquals(0, Facing.NORTH.du());
        assertEquals(1, Facing.NORTH.dw());
        assertEquals(1, Facing.EAST.du());
        assertEquals(0, Facing.EAST.dw());
        assertEquals(-1, Facing.SOUTH.dw());
        assertEquals(-1, Facing.WEST.du());
    }

    @Test
    void parseIsCaseInsensitiveAndRejectsUnknown() {
        assertEquals(Facing.EAST, Facing.parse("east"));
        assertEquals(Facing.EAST, Facing.parse(" EAST "));
        assertEquals("east", Facing.EAST.lower());
        assertThrows(IllegalArgumentException.class, () -> Facing.parse("up"));
    }

    @Test
    void dir6Parse() {
        assertEquals(Dir6.UP, Dir6.parse("up"));
        assertEquals("north", Dir6.NORTH.lower());
        assertThrows(IllegalArgumentException.class, () -> Dir6.parse("sideways"));
    }
}
