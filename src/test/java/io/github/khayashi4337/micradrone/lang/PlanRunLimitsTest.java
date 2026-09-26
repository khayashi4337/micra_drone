package io.github.khayashi4337.micradrone.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlanRunLimitsTest {
    @Test
    void limitsMustBePositive() {
        // A zero or negative budget would refuse every script at (or before) its first counted
        // step; the constructor rejects it where it is built and names the field that was wrong.
        for (long bad : new long[]{0, -1}) {
            IllegalArgumentException steps = assertThrows(IllegalArgumentException.class,
                    () -> new PlanRunLimits(bad, 1));
            assertTrue(steps.getMessage().contains("maxSteps"), steps.getMessage());
            IllegalArgumentException millis = assertThrows(IllegalArgumentException.class,
                    () -> new PlanRunLimits(1, bad));
            assertTrue(millis.getMessage().contains("maxMillis"), millis.getMessage());
        }
        PlanRunLimits smallest = new PlanRunLimits(1, 1);
        assertEquals(1, smallest.maxSteps());
        assertEquals(1, smallest.maxMillis());
    }
}
