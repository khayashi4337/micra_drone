package io.github.khayashi4337.micradrone.lang;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TaskRegistryTest {

    @Test
    void reservingTheSameNameTwiceFailsTheSecondTime() {
        TaskRegistry registry = new TaskRegistry();
        assertTrue(registry.tryReserve("a"));
        assertFalse(registry.tryReserve("a"));
    }

    @Test
    void releasingFreesTheNameForReuse() {
        TaskRegistry registry = new TaskRegistry();
        assertTrue(registry.tryReserve("a"));
        registry.release("a");
        assertTrue(registry.tryReserve("a"));
    }

    @Test
    void releasingAnUnreservedNameIsANoOp() {
        TaskRegistry registry = new TaskRegistry();
        registry.release("never-reserved"); // must not throw
    }

    @Test
    void theSeventeenthConcurrentReservationIsRejected() {
        TaskRegistry registry = new TaskRegistry();
        for (int i = 0; i < 16; i++) {
            assertTrue(registry.tryReserve("task-" + i), "reservation " + i + " should have succeeded");
        }
        assertFalse(registry.tryReserve("task-16"), "the 17th concurrent reservation should be rejected");
    }

    @Test
    void releasingOneFreesCapacityForAnother() {
        TaskRegistry registry = new TaskRegistry();
        for (int i = 0; i < 16; i++) {
            registry.tryReserve("task-" + i);
        }
        assertFalse(registry.tryReserve("overflow"));
        registry.release("task-0");
        assertTrue(registry.tryReserve("overflow"));
    }

    @Test
    void stopAllInterruptsEveryBoundThread() throws InterruptedException {
        TaskRegistry registry = new TaskRegistry();
        registry.tryReserve("a");
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        thread.setDaemon(true);
        registry.bind("a", thread);
        thread.start();

        registry.stopAll();
        thread.join(2000);
        assertFalse(thread.isAlive(), "stopAll() should have interrupted the bound thread");
    }

    @Test
    void stopAllClosesIntakeUntilReopen() {
        TaskRegistry registry = new TaskRegistry();
        registry.stopAll();
        assertFalse(registry.tryReserve("a"), "no new reservations should be accepted after stopAll()");
        registry.reopen();
        assertTrue(registry.tryReserve("a"), "reservations should work again after reopen()");
    }

    @Test
    void reservingDuringStopAllDoesNotLeakASlot() {
        // A name that was never actually accepted (rejected by the accepting=false gate) must not
        // have consumed a capacity slot - otherwise capacity would silently shrink over repeated
        // Stop/Run cycles.
        TaskRegistry registry = new TaskRegistry();
        registry.stopAll();
        assertFalse(registry.tryReserve("rejected"));
        registry.reopen();
        for (int i = 0; i < 16; i++) {
            assertTrue(registry.tryReserve("task-" + i), "reservation " + i + " should have succeeded after reopen()");
        }
    }
}
