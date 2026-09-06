package io.github.khayashi4337.micradrone.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PumpkinEffectTuningTest {

    @Test
    void theBrokenCellItselfIsRingZeroAndCrumblesWithNoDelay() {
        assertEquals(0, PumpkinEffectTuning.collapseRing(10, -4, 10, -4));
        assertEquals(0, PumpkinEffectTuning.collapseDelayTicks(0));
    }

    @Test
    void ringsAreSquareWavesAroundTheBrokenCell() {
        // Every cell of the 3x3 around the break is ring 1 - corners included, not just the 4 neighbours.
        assertEquals(1, PumpkinEffectTuning.collapseRing(0, 0, 1, 0));
        assertEquals(1, PumpkinEffectTuning.collapseRing(0, 0, 1, 1));
        assertEquals(1, PumpkinEffectTuning.collapseRing(0, 0, -1, -1));
        // Further out, the larger axis distance decides.
        assertEquals(3, PumpkinEffectTuning.collapseRing(0, 0, 2, 3));
        assertEquals(3, PumpkinEffectTuning.collapseRing(5, 5, 2, 8));
    }

    @Test
    void eachRingCrumblesAFixedNumberOfTicksAfterThePreviousOne() {
        for (int ring = 1; ring < 8; ring++) {
            assertEquals(PumpkinEffectTuning.COLLAPSE_RING_DELAY_TICKS,
                    PumpkinEffectTuning.collapseDelayTicks(ring) - PumpkinEffectTuning.collapseDelayTicks(ring - 1));
        }
    }

    @Test
    void biggerPumpkinsSoundDeeperDownToAFloor() {
        assertEquals(PumpkinEffectTuning.PITCH_AT_SMALLEST,
                PumpkinEffectTuning.pitchForSide(PumpkinEffectTuning.SMALLEST_FUSED_SIDE));
        float previous = PumpkinEffectTuning.pitchForSide(PumpkinEffectTuning.SMALLEST_FUSED_SIDE);
        for (int side = PumpkinEffectTuning.SMALLEST_FUSED_SIDE + 1; side <= 10; side++) {
            float pitch = PumpkinEffectTuning.pitchForSide(side);
            assertTrue(pitch <= previous, "side " + side + " should not be higher than side " + (side - 1));
            assertTrue(pitch >= PumpkinEffectTuning.PITCH_MIN, "side " + side + " fell below the floor");
            previous = pitch;
        }
        assertEquals(PumpkinEffectTuning.PITCH_MIN, PumpkinEffectTuning.pitchForSide(64));
    }

    @Test
    void aLoneCellNeverSoundsHigherThanTheSmallestFusedPumpkin() {
        assertEquals(PumpkinEffectTuning.PITCH_AT_SMALLEST, PumpkinEffectTuning.pitchForSide(1));
    }
}
