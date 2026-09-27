package io.github.khayashi4337.micradrone.build.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class IntPosTest {
    @Test
    void plusAddsEachAxis() {
        assertEquals(new IntPos(4, 6, 2), new IntPos(1, 2, 3).plus(3, 4, -1));
    }
}
