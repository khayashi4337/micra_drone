package io.github.khayashi4337.micradrone.drone;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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

    /**
     * Runs every entry whose readyAtTick has arrived, in submission order - regardless of where in
     * the queue it sits. Entries no longer arrive in non-decreasing readyAtTick order now that
     * sleep_ticks() lets a script request an arbitrarily long delay (previously every delay was the
     * same fixed constant, so a simple peek-the-head loop happened to work): a still-pending
     * far-future entry from one long sleep_ticks() call must not block a later-submitted but
     * nearer-future entry (e.g. another task's move()) from running on time.
     */
    public void tick(long currentTick) {
        List<Entry> ready = new ArrayList<>();
        Iterator<Entry> it = pending.iterator();
        while (it.hasNext()) {
            Entry entry = it.next();
            if (entry.readyAtTick() <= currentTick) {
                ready.add(entry);
                it.remove();
            }
        }
        for (Entry entry : ready) {
            entry.apply().run();
        }
    }
}
