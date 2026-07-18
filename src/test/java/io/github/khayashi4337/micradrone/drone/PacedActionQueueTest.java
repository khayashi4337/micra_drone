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
}
