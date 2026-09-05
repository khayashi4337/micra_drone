package io.github.khayashi4337.micradrone.lang;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class TaskRegistryTest {

    private static Thread inertThread() {
        // Never started, so binding/interrupting it in these tests has no real-world side effect -
        // these tests only exercise TaskRegistry's own bookkeeping, not real task execution.
        return new Thread();
    }

    @Test
    void reservingTheSameNameTwiceFailsTheSecondTime() {
        TaskRegistry registry = new TaskRegistry();
        assertTrue(registry.tryReserveAndBind("a", inertThread()));
        assertFalse(registry.tryReserveAndBind("a", inertThread()));
    }

    @Test
    void releasingFreesTheNameForReuse() {
        TaskRegistry registry = new TaskRegistry();
        assertTrue(registry.tryReserveAndBind("a", inertThread()));
        registry.release("a");
        assertTrue(registry.tryReserveAndBind("a", inertThread()));
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
            assertTrue(registry.tryReserveAndBind("task-" + i, inertThread()), "reservation " + i + " should have succeeded");
        }
        assertFalse(registry.tryReserveAndBind("task-16", inertThread()), "the 17th concurrent reservation should be rejected");
    }

    @Test
    void releasingOneFreesCapacityForAnother() {
        TaskRegistry registry = new TaskRegistry();
        for (int i = 0; i < 16; i++) {
            registry.tryReserveAndBind("task-" + i, inertThread());
        }
        assertFalse(registry.tryReserveAndBind("overflow", inertThread()));
        registry.release("task-0");
        assertTrue(registry.tryReserveAndBind("overflow", inertThread()));
    }

    @Test
    void stopAllInterruptsEveryBoundThread() throws InterruptedException {
        TaskRegistry registry = new TaskRegistry();
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        thread.setDaemon(true);
        registry.tryReserveAndBind("a", thread);
        thread.start();

        registry.stopAll();
        thread.join(2000);
        assertFalse(thread.isAlive(), "stopAll() should have interrupted the bound thread");
    }

    @Test
    void stopAllClosesIntakeUntilReopen() {
        TaskRegistry registry = new TaskRegistry();
        registry.stopAll();
        assertFalse(registry.tryReserveAndBind("a", inertThread()), "no new reservations should be accepted after stopAll()");
        registry.reopen();
        assertTrue(registry.tryReserveAndBind("a", inertThread()), "reservations should work again after reopen()");
    }

    @Test
    void reservingDuringStopAllDoesNotLeakASlot() {
        // A name that was never actually accepted (rejected by the accepting=false gate) must not
        // have consumed a capacity slot - otherwise capacity would silently shrink over repeated
        // Stop/Run cycles.
        TaskRegistry registry = new TaskRegistry();
        registry.stopAll();
        assertFalse(registry.tryReserveAndBind("rejected", inertThread()));
        registry.reopen();
        for (int i = 0; i < 16; i++) {
            assertTrue(registry.tryReserveAndBind("task-" + i, inertThread()), "reservation " + i + " should have succeeded after reopen()");
        }
    }

    /**
     * Best-effort regression test for the race Codex's review found: a naive {@code volatile
     * boolean accepting} checked at the top of {@code tryReserve} and a separate later {@code
     * bind} call left a window where a concurrently-running {@code stopAll()} could miss a
     * reservation that was "in flight" - it would only see an inert placeholder, interrupt that (a
     * no-op), and the real thread would go on to start completely unstopped.
     *
     * <p><b>The actual correctness guarantee here is a logical one, not this test</b>:
     * {@code tryReserveAndBind} and {@code stopAll} both hold the same monitor ({@link
     * #lock}) for their entire body, so the two can never interleave - every successful
     * reservation either fully completes (with its thread visible in {@code tasks}) before a
     * given {@code stopAll()} call starts, and is therefore interrupted by it, or is attempted
     * after that {@code stopAll()} has already set {@code accepting = false} under the same lock
     * and is rejected outright. There is no third case. This test hammers the two operations from
     * many threads to give a *chance* of empirically catching a future regression that breaks
     * that mutual exclusion, but a race this narrow is not guaranteed to be caught by any fixed
     * number of stress iterations - deliberately reintroducing the original bug during review (by
     * temporarily removing {@code stopAll}'s {@code synchronized(lock)}) did not reliably fail
     * this test even across repeated runs, which is a known, accepted limitation of stress-testing
     * a race this narrow rather than a gap in the fix itself (verified instead by the mutual-
     * exclusion argument above).
     */
    @Test
    void concurrentTryReserveAndStopAllNeverLeavesAThreadUnstoppable() throws Exception {
        TaskRegistry registry = new TaskRegistry();
        int attempts = 200;
        CyclicBarrier barrier = new CyclicBarrier(attempts + 1);
        AtomicInteger accepted = new AtomicInteger();
        List<Thread> boundThreads = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        List<Thread> reservers = new java.util.ArrayList<>();

        for (int i = 0; i < attempts; i++) {
            String name = "task-" + i;
            Thread bound = new Thread(() -> {
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            bound.setDaemon(true);
            Thread reserver = new Thread(() -> {
                try {
                    barrier.await();
                } catch (Exception ignored) {
                    return;
                }
                if (registry.tryReserveAndBind(name, bound)) {
                    accepted.incrementAndGet();
                    boundThreads.add(bound);
                    bound.start();
                }
            });
            reserver.setDaemon(true);
            reservers.add(reserver);
            reserver.start();
        }

        // Release every reserver+stopAll() to race at (roughly) the same time: the barrier holds
        // every reserver thread until this count is also reached, then a fresh caller thread races
        // stopAll() in immediately after.
        CountDownLatch stopAllDone = new CountDownLatch(1);
        Thread stopper = new Thread(() -> {
            try {
                barrier.await();
            } catch (Exception ignored) {
                return;
            }
            registry.stopAll();
            stopAllDone.countDown();
        });
        stopper.setDaemon(true);
        stopper.start();

        for (Thread reserver : reservers) {
            reserver.join(5000);
        }
        stopAllDone.await(5, java.util.concurrent.TimeUnit.SECONDS);

        // Give every accepted task's own thread a moment to actually receive and act on the
        // interrupt that either its own start-then-immediately-race-with-stopAll, or stopAll()
        // itself, should have delivered by now.
        long deadline = System.currentTimeMillis() + 2000;
        for (Thread bound : boundThreads) {
            while (bound.isAlive() && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }
        }

        for (Thread bound : boundThreads) {
            assertFalse(bound.isAlive(),
                    "every thread accepted by tryReserveAndBind must have been interrupted by a stopAll() racing right after it - "
                            + accepted.get() + " were accepted");
        }
    }
}
