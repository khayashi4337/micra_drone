package io.github.khayashi4337.micradrone.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class PacedActionQueueTest {

    @Test
    void doesNotRunEntriesBeforeTheirReadyTick() {
        PacedActionQueue queue = new PacedActionQueue();
        boolean[] ran = {false};
        queue.submit(10, () -> ran[0] = true);

        queue.tick(9);
        assertFalse(ran[0]);

        queue.tick(10);
        assertTrue(ran[0]);
    }

    @Test
    void runsMultipleDueEntriesInSubmissionOrder() {
        PacedActionQueue queue = new PacedActionQueue();
        List<String> order = new ArrayList<>();
        queue.submit(5, () -> order.add("a"));
        queue.submit(5, () -> order.add("b"));
        queue.submit(20, () -> order.add("c"));

        queue.tick(5);
        assertEquals(List.of("a", "b"), order);

        queue.tick(20);
        assertEquals(List.of("a", "b", "c"), order);
    }

    @Test
    void tickPastDueDoesNotRerun() {
        PacedActionQueue queue = new PacedActionQueue();
        int[] count = {0};
        queue.submit(1, () -> count[0]++);

        queue.tick(1);
        queue.tick(2);
        queue.tick(100);

        assertEquals(1, count[0]);
    }

    /**
     * Regression test for the head-blocking bug (Codex review finding): before sleep_ticks()
     * existed, every delay was the same fixed constant, so entries always arrived in non-decreasing
     * readyAtTick order and a simple "peek the head, stop at the first not-yet-ready entry" loop
     * happened to work. sleep_ticks() lets a script submit an arbitrarily far-future entry (e.g.
     * from a long sleep) before a later, nearer-future entry (e.g. another task's move()) is
     * submitted - the nearer one must still run on time instead of waiting behind the far one.
     */
    @Test
    void aFarFutureEntrySubmittedFirstDoesNotBlockANearerFutureEntrySubmittedLater() {
        PacedActionQueue queue = new PacedActionQueue();
        List<String> order = new ArrayList<>();
        queue.submit(1000, () -> order.add("far-future"));
        queue.submit(5, () -> order.add("near-future"));

        queue.tick(5);
        assertEquals(List.of("near-future"), order, "the nearer entry should run without waiting for the far one");

        queue.tick(1000);
        assertEquals(List.of("near-future", "far-future"), order);
    }

    /**
     * Regression test for a bug in the head-blocking fix itself (Codex review finding): an earlier
     * draft collected every ready entry into a list first, then ran them - so if the first one
     * threw, every later one already collected (and already removed from the queue) was lost
     * forever, its own action never running and never getting a chance on a later tick either.
     * Removing and running one entry at a time (not collect-then-run-all) means a throwing entry
     * only loses itself; anything the iterator hasn't reached yet is untouched and still pending.
     */
    @Test
    void anEarlierEntryThrowingDoesNotLoseALaterReadyEntry() {
        PacedActionQueue queue = new PacedActionQueue();
        List<String> ran = new ArrayList<>();
        queue.submit(5, () -> { throw new RuntimeException("boom"); });
        queue.submit(5, () -> ran.add("second"));

        try {
            queue.tick(5);
        } catch (RuntimeException expected) {
            // the first entry's own exception propagating is the pre-existing, unchanged behavior
        }

        // The second entry must still be pending (not silently discarded) and run on a later tick.
        queue.tick(5);
        assertEquals(List.of("second"), ran, "the second entry should still run on a later tick, not be lost");
    }
}
