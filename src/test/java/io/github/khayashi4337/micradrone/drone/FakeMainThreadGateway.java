package io.github.khayashi4337.micradrone.drone;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Deterministic test double for {@link MainThreadGateway}. Work submitted via
 * {@link #runOnMainThread} is only queued; tests drive it explicitly with
 * {@link #pump()} and advance the simulated clock with {@link #tick(long, PacedActionQueue)},
 * mirroring how a real worker thread hands off to Minecraft's main thread.
 */
final class FakeMainThreadGateway implements MainThreadGateway {
    private final Deque<Runnable> queued = new ArrayDeque<>();
    private volatile long tick = 0;

    @Override
    public synchronized void runOnMainThread(Runnable task) {
        queued.add(task);
    }

    @Override
    public long currentTick() {
        return tick;
    }

    synchronized boolean hasQueuedWork() {
        return !queued.isEmpty();
    }

    /** Runs all currently queued main-thread tasks. */
    synchronized void pump() {
        while (!queued.isEmpty()) {
            queued.poll().run();
        }
    }

    /** Advances the simulated clock to {@code newTick} and drains due paced actions. */
    void advanceTo(long newTick, PacedActionQueue queue) {
        this.tick = newTick;
        queue.tick(newTick);
    }

    /** Busy-waits (bounded) until work has been queued from another thread. */
    void awaitQueuedWork(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!hasQueuedWork()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for work on the main thread gateway");
            }
            Thread.sleep(1);
        }
    }
}
