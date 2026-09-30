package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ServerWorkerPoolTest {
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final long WAIT_MS = 5_000L;

    private final ConcurrentLinkedQueue<Runnable> main = new ConcurrentLinkedQueue<>();

    /** Runs the main-thread queue until {@code done} is true or the time is up; the test thread plays the server thread. */
    private void pumpUntil(java.util.function.BooleanSupplier done) throws InterruptedException {
        long end = System.currentTimeMillis() + WAIT_MS;
        while (!done.getAsBoolean()) {
            Runnable r = main.poll();
            if (r != null) {
                r.run();
            } else if (System.currentTimeMillis() > end) {
                throw new AssertionError("timed out");
            } else {
                Thread.sleep(1);
            }
        }
    }

    @Test
    void resultsComeBackOnTheMainThreadAndOnePerOwner() throws Exception {
        try (ServerWorkerPool pool = new ServerWorkerPool(2, 4, main::add)) {
            CountDownLatch gate = new CountDownLatch(1);
            List<WorkResult<Integer>> got = new ArrayList<>();
            assertTrue(pool.submit(A, () -> {
                gate.await(WAIT_MS, TimeUnit.MILLISECONDS);
                return 42;
            }, got::add));
            assertTrue(pool.busy(A));
            assertFalse(pool.submit(A, () -> 1, r -> { }), "one heavy job per player at a time");
            assertTrue(pool.submit(B, () -> 7, got::add), "another player is not blocked");
            gate.countDown();
            pumpUntil(() -> got.size() == 2);
            assertTrue(got.contains(new WorkResult.Done<>(42)));
            assertTrue(got.contains(new WorkResult.Done<>(7)));
            assertFalse(pool.busy(A));
        }
    }

    @Test
    void failuresAndCancelsAreResultsNotCrashes() throws Exception {
        try (ServerWorkerPool pool = new ServerWorkerPool(1, 4, main::add)) {
            List<WorkResult<Integer>> got = new ArrayList<>();
            pool.<Integer>submit(A, () -> {
                throw new IllegalStateException("boom");
            }, got::add);
            pumpUntil(() -> got.size() == 1);
            assertEquals("boom", assertInstanceOf(WorkResult.Failed.class, got.get(0)).error().getMessage());
            CountDownLatch never = new CountDownLatch(1);
            pool.submit(B, () -> {
                never.await(WAIT_MS, TimeUnit.MILLISECONDS);
                return 1;
            }, got::add);
            assertTrue(pool.cancel(B));
            pumpUntil(() -> got.size() == 2);
            assertInstanceOf(WorkResult.Cancelled.class, got.get(1));
        }
    }

    @Test
    void theQueueIsBounded() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        try (ServerWorkerPool pool = new ServerWorkerPool(1, 1, main::add)) {
            assertTrue(pool.submit(new UUID(1, 1), () -> {
                started.countDown();
                return gate.await(WAIT_MS, TimeUnit.MILLISECONDS);
            }, r -> { }));
            assertTrue(started.await(WAIT_MS, TimeUnit.MILLISECONDS), "the first job holds the only thread, not the queue");
            assertTrue(pool.submit(new UUID(1, 2), () -> 1, r -> { }));
            assertFalse(pool.submit(new UUID(1, 3), () -> 1, r -> { }), "a full queue refuses instead of piling up");
            gate.countDown();
        }
    }

    @Test
    void aCancelledJobKeepsItsOwnersSlotUntilItsWorkHasLeftTheThread() throws Exception {
        try (ServerWorkerPool pool = new ServerWorkerPool(2, 4, main::add)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            List<WorkResult<Integer>> got = new ArrayList<>();
            assertTrue(pool.submit(A, token -> {
                started.countDown();
                while (release.getCount() > 0) {
                    Thread.onSpinWait(); // deaf to interrupts, like a long pure computation
                }
                return 1;
            }, got::add));
            assertTrue(started.await(WAIT_MS, TimeUnit.MILLISECONDS));
            assertTrue(pool.cancel(A));
            assertTrue(pool.busy(A), "the work is still running: the slot is not free yet");
            assertFalse(pool.submit(A, () -> 2, r -> { }), "no second compile beside the cancelled one");
            release.countDown();
            pumpUntil(() -> got.size() == 1);
            assertInstanceOf(WorkResult.Cancelled.class, got.get(0));
            assertFalse(pool.busy(A));
            assertTrue(pool.submit(A, () -> 3, got::add), "now the slot is free");
            pumpUntil(() -> got.size() == 2);
        }
    }

    @Test
    void cancelledWorkSeesItsToken() throws Exception {
        try (ServerWorkerPool pool = new ServerWorkerPool(1, 4, main::add)) {
            CountDownLatch started = new CountDownLatch(1);
            List<WorkResult<Boolean>> got = new ArrayList<>();
            pool.submit(A, token -> {
                started.countDown();
                while (!token.cancelled()) {
                    Thread.onSpinWait();
                }
                return true;
            }, got::add);
            assertTrue(started.await(WAIT_MS, TimeUnit.MILLISECONDS));
            pool.cancel(A);
            pumpUntil(() -> got.size() == 1);
            assertInstanceOf(WorkResult.Cancelled.class, got.get(0), "the work stopped on its own at the token");
        }
    }

    @Test
    void closingCancelsRunningTokensAndAnswersQueuedWork() throws Exception {
        try (ServerWorkerPool pool = new ServerWorkerPool(1, 4, main::add)) {
            CountDownLatch started = new CountDownLatch(1);
            List<WorkResult<Integer>> got = new ArrayList<>();
            pool.<Integer>submit(A, token -> {
                started.countDown();
                while (!token.cancelled()) {
                    Thread.onSpinWait(); // deaf to interrupts, like a long pure computation
                }
                return 1;
            }, got::add);
            assertTrue(started.await(WAIT_MS, TimeUnit.MILLISECONDS));
            assertTrue(pool.submit(B, () -> 2, got::add), "the only thread is busy: B waits in the queue");
            assertTimeoutPreemptively(Duration.ofSeconds(2), pool::close,
                    "close() ends work that only polls its token and never interrupts cleanly");
            pumpUntil(() -> got.size() == 2);
            assertInstanceOf(WorkResult.Cancelled.class, got.get(0), "the running job ends cancelled");
            assertInstanceOf(WorkResult.Cancelled.class, got.get(1), "a queued job gets its end result, not silence");
        }
    }
}
