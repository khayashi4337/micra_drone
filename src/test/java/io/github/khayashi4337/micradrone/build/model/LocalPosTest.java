package io.github.khayashi4337.micradrone.build.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LocalPosTest {
    @Test
    void plusAddsEachAxis() {
        assertEquals(new LocalPos(4, 6, 2), new LocalPos(1, 2, 3).plus(3, 4, -1));
    }
}
