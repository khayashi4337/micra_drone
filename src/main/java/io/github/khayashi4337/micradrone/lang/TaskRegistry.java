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
 *
 * <p><b>Reservation and binding are a single atomic operation</b> ({@link #tryReserveAndBind}), not
 * a separate reserve-then-bind pair: an earlier version had {@code tryReserve(name)} hand back a
 * placeholder slot that the caller filled in with the real {@link Thread} via a later {@code
 * bind(name, thread)} call, but a concurrent {@link #stopAll} landing in that gap would only ever
 * see the placeholder (an inert, never-started thread) and let the real thread start completely
 * unstopped right after - defeating the "Stop means everything this script started is stopped"
 * guarantee (Codex review finding). Combining them into one call made under {@link #lock} closes
 * that window: {@link #stopAll} and {@link #tryReserveAndBind} can never interleave.
 */
public final class TaskRegistry {
    /** Concurrent-task cap: a safety valve against a script thread-bombing the server via a tight create_task loop. */
    private static final int MAX_CONCURRENT_TASKS = 16;

    /**
     * Guards {@code accepting} together with every read/mutation of {@link #tasks}/{@link #slots}
     * that must be atomic with respect to it - see the class javadoc. A single {@code synchronized}
     * monitor is simplest and cheap enough here: task creation/teardown are not a hot path.
     */
    private final Object lock = new Object();
    // A Semaphore makes the capacity check atomic (a plain `tasks.size() >= MAX` check-then-act
    // would let multiple concurrent callers all observe "under capacity" and all proceed).
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT_TASKS);
    private final Map<String, Thread> tasks = new ConcurrentHashMap<>();
    // Closed by stopAll() so a task can't slip in and start after a Stop/re-Run has already begun
    // tearing down the previous generation of tasks; reopened explicitly for the next Run.
    private boolean accepting = true;

    /**
     * Atomically reserves {@code name} and binds {@code thread} to it - the caller must not yet
     * have called {@code thread.start()}. Returns false (the thread is never bound and the caller
     * must not start it) if the name is already in use, the concurrent-task cap is reached, or
     * {@link #stopAll} has closed intake and {@link #reopen} hasn't been called since.
     */
    public boolean tryReserveAndBind(String name, Thread thread) {
        synchronized (lock) {
            if (!accepting) {
                return false;
            }
            if (!slots.tryAcquire()) {
                return false;
            }
            if (tasks.putIfAbsent(name, thread) != null) {
                slots.release(); // name collision - give the slot back
                return false;
            }
            return true;
        }
    }

    /** Releases a task's name/slot (call from the task's own finally block once it's done). */
    public void release(String name) {
        if (tasks.remove(name) != null) {
            slots.release();
        }
    }

    /** Interrupts every live task and stops accepting new ones until {@link #reopen()}. */
    public void stopAll() {
        synchronized (lock) {
            accepting = false;
            for (Thread t : tasks.values()) {
                t.interrupt();
            }
        }
    }

    /** Re-opens intake after stopAll() - call at the start of a fresh Run. */
    public void reopen() {
        synchronized (lock) {
            accepting = true;
        }
    }
}
