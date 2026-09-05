package io.github.khayashi4337.micradrone.lang;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * create_task's live task bookkeeping: the name->Thread map, the concurrent-task cap, and the
 * accept/reject gate stopAll() closes. Owned by
 * {@link io.github.khayashi4337.micradrone.drone.DroneControllerBlockEntity} for the controller's
 * whole lifetime (across Runs) - see docs/design/lang_rtos_task_foundation.md's "TaskRegistry/
 * InterruptTableの所有者について" for why this must NOT be owned by DroneScriptRunner (a new one
 * is constructed on every Run, which would orphan any tasks a previous run started).
 */
public final class TaskRegistry {
    /**
     * A never-started placeholder Thread used only to occupy a name between tryReserve and bind.
     * interrupt()ing it (e.g. from a stopAll() that races with a reservation still in flight) is a
     * complete no-op - unlike using the calling thread itself as the placeholder, which would risk
     * interrupting an unrelated caller.
     */
    private static final Thread RESERVING_PLACEHOLDER = new Thread();

    /** Concurrent-task cap: a safety valve against a script thread-bombing the server via a tight create_task loop. */
    private static final int MAX_CONCURRENT_TASKS = 16;

    // A Semaphore makes the capacity check atomic (a plain `tasks.size() >= MAX` check-then-act
    // would let multiple concurrent callers all observe "under capacity" and all proceed).
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT_TASKS);
    private final Map<String, Thread> tasks = new ConcurrentHashMap<>();
    // Closed by stopAll() so a task can't slip in and start after a Stop/re-Run has already begun
    // tearing down the previous generation of tasks; reopened explicitly for the next Run.
    private volatile boolean accepting = true;

    /** Reserves {@code name}. False if it's already in use, the concurrent-task cap is reached, or stopAll() has closed intake. */
    public boolean tryReserve(String name) {
        if (!accepting) {
            return false;
        }
        if (!slots.tryAcquire()) {
            return false;
        }
        if (tasks.putIfAbsent(name, RESERVING_PLACEHOLDER) != null) {
            slots.release(); // name collision - give the slot back
            return false;
        }
        return true;
    }

    /** Binds the real task Thread to an already-reserved name (call after tryReserve, before start()). */
    public void bind(String name, Thread thread) {
        tasks.put(name, thread);
    }

    /** Releases a task's name/slot (call from the task's own finally block once it's done). */
    public void release(String name) {
        if (tasks.remove(name) != null) {
            slots.release();
        }
    }

    /** Interrupts every live task and stops accepting new ones until {@link #reopen()}. */
    public void stopAll() {
        accepting = false;
        for (Thread t : tasks.values()) {
            t.interrupt();
        }
    }

    /** Re-opens intake after stopAll() - call at the start of a fresh Run. */
    public void reopen() {
        accepting = true;
    }
}
