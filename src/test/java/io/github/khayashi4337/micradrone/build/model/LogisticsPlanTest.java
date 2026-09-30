package io.github.khayashi4337.micradrone.build.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LogisticsPlanTest {
    private static LogisticsPlan.CargoFlow flow(double perMin) {
        return new LogisticsPlan.CargoFlow("minecraft:iron_ingot", perMin, "dock-a", "dock-b");
    }

    @Test
    void aFlowRateThatIsNotAFiniteNumberCannotBeBuilt() {
        // contentHash refuses NaN and the infinities, so a plan holding one could be built but never hashed
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> flow(bad), String.valueOf(bad));
            assertTrue(e.getMessage().contains("perMin"), "the message names the field: " + e.getMessage());
        }
    }

    @Test
    void aNegativeZeroFlowRateIsStoredAsPositiveZero() {
        // -0.0 is written as "0" in a script and comes back +0.0, so the model never keeps the minus sign
        LogisticsPlan.CargoFlow negative = flow(-0.0);
        assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(negative.perMin()));
        assertEquals(flow(0.0), negative, "equal to the flow written with a plain zero");
    }

    @Test
    void otherFlowRatesAreKeptAsTheyAre() {
        for (double rate : new double[] {12.5, -3.5, 30.0, Double.MIN_VALUE, Double.MAX_VALUE}) {
            assertEquals(Double.doubleToRawLongBits(rate), Double.doubleToRawLongBits(flow(rate).perMin()), String.valueOf(rate));
        }
    }
}
