package io.github.khayashi4337.micradrone.build.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BoxTest {
    @Test
    void containsIsInclusiveOnBothEnds() {
        Box b = new Box(0, 0, 0, 2, 3, 4);
        assertTrue(b.contains(0, 0, 0));
        assertTrue(b.contains(2, 3, 4));
        assertFalse(b.contains(3, 0, 0));
        assertFalse(b.contains(0, -1, 0));
        assertEquals(3L * 4L * 5L, b.volume());
    }

    @Test
    void constructorRejectsInvertedBounds() {
        assertThrows(IllegalArgumentException.class, () -> new Box(1, 0, 0, 0, 0, 0));
    }

    @Test
    void ofNormalizesTheCorners() {
        assertEquals(new Box(0, 1, 2, 3, 4, 5), Box.of(3, 4, 5, 0, 1, 2));
    }
}
