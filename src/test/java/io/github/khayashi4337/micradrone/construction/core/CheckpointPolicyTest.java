package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CheckpointPolicyTest {
    /** The sentinel Checkpoints starts with: a point that never happened sits far in the past. */
    private static final long STARTUP = Long.MIN_VALUE / 2;

    @Test
    void nothingOpenIsNeverDue() {
        // no waiting job and no run past the durable point: there is nothing a save would settle
        assertFalse(CheckpointPolicy.due(false, false, 10_000, 0, 0));
        assertFalse(CheckpointPolicy.due(false, false, 10_000, STARTUP, STARTUP));
        assertFalse(CheckpointPolicy.due(false, false, 0, STARTUP, STARTUP));
    }

    @Test
    void aWaitingJobIsDueFiveSecondsAfterTheLastTry() {
        long lastTry = 5_000;
        // 20 ticks/s: 99 ticks is not yet five seconds, 100 ticks is exactly five
        assertFalse(CheckpointPolicy.due(true, false, lastTry + 99, lastTry, lastTry));
        assertTrue(CheckpointPolicy.due(true, false, lastTry + 100, lastTry, lastTry));
    }

    @Test
    void aWaitingJobDoesNotWaitForTheFiveMinuteInterval() {
        long lastTry = 5_000;
        // the last point was marked on that very try: the five-minute cadence must not hold a
        // waiting job back, and five seconds after the try a new point is already due
        assertTrue(CheckpointPolicy.due(true, false, lastTry + 100, lastTry, lastTry + 100));
        assertTrue(CheckpointPolicy.due(true, true, lastTry + 100, lastTry, lastTry + 100));
        assertFalse(CheckpointPolicy.due(true, false, lastTry + 99, lastTry, lastTry + 99));
    }

    @Test
    void openRunsKeepTheOldThrottle() {
        // runs open past the durable point but nobody waits: one try a minute AND one point per
        // five minutes, on the autosave's own cadence
        assertFalse(CheckpointPolicy.due(false, true, 11_199, 10_000, 0)); // 1199 ticks since the try
        assertFalse(CheckpointPolicy.due(false, true, 15_999, 10_000, 10_000)); // 5999 ticks since the point
        assertTrue(CheckpointPolicy.due(false, true, 16_000, 10_000, 10_000)); // both satisfied
    }

    @Test
    void theFirstPointAfterStartUpIsDueRightAway() {
        assertTrue(CheckpointPolicy.due(true, false, 0, STARTUP, STARTUP));
        assertTrue(CheckpointPolicy.due(false, true, 0, STARTUP, STARTUP));
    }

    @Test
    void aWaitingJobKeepsItsFiveSecondSpacingWhileRunsStayOpen() {
        long lastTry = 5_000;
        // wanted wins over the open-runs throttle: both set, the five-second rule applies
        assertFalse(CheckpointPolicy.due(true, true, lastTry + 99, lastTry, lastTry));
        assertTrue(CheckpointPolicy.due(true, true, lastTry + 100, lastTry, lastTry));
    }
}
