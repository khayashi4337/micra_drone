package io.github.khayashi4337.micradrone.drone;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Holds actions that must not run until a given tick has been reached, so a
 * drone action's visible effect happens at a paced rate rather than
 * instantly. {@link #submit} may be called from any thread (it only enqueues);
 * {@link #tick} must be called from the main thread once per game tick.
 */
public final class PacedActionQueue {
    private record Entry(long readyAtTick, Runnable apply) {}

    private final Queue<Entry> pending = new ConcurrentLinkedQueue<>();

    public void submit(long readyAtTick, Runnable apply) {
        pending.add(new Entry(readyAtTick, apply));
    }

    /** Runs every entry whose readyAtTick has arrived, in submission order. */
    public void tick(long currentTick) {
        while (true) {
            Entry head = pending.peek();
            if (head == null || head.readyAtTick() > currentTick) {
                return;
            }
            pending.poll();
            head.apply().run();
        }
    }
}
