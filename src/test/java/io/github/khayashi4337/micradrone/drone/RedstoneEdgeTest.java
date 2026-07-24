package io.github.khayashi4337.micradrone.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RedstoneEdgeTest {

    @Test
    void risingThenSteadyThenFalling() {
        RedstoneEdge edge = new RedstoneEdge();
        assertEquals(RedstoneEdge.Edge.RISING, edge.update(true));
        assertEquals(RedstoneEdge.Edge.NONE, edge.update(true));
        assertEquals(RedstoneEdge.Edge.FALLING, edge.update(false));
        assertEquals(RedstoneEdge.Edge.NONE, edge.update(false));
    }

    @Test
    void startsUnpoweredSoAnInitialOffIsNotAFallingEdge() {
        RedstoneEdge edge = new RedstoneEdge();
        assertEquals(RedstoneEdge.Edge.NONE, edge.update(false));
    }

    @Test
    void reRisesAfterAFullCycle() {
        RedstoneEdge edge = new RedstoneEdge();
        edge.update(true);
        edge.update(false);
        assertEquals(RedstoneEdge.Edge.RISING, edge.update(true));
    }
}
