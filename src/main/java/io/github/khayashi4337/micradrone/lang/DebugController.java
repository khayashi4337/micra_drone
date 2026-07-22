package io.github.khayashi4337.micradrone.lang;

import java.util.Set;

/**
 * Debugger control shared between the script's worker thread and the game: breakpoints, pause/
 * resume, statement stepping, and step-out-of-the-current-loop. The worker thread reports in via
 * {@link #onStatement} (called by {@link Interpreter} before every statement) and blocks right
 * there when a pause is due; the other methods are called from the server main thread (via the
 * IDE's debug payloads) and only flip flags / wake the worker. Minecraft-free and self-contained
 * so the whole protocol is unit-testable with plain threads.
 *
 * <p>The language has no user-defined functions yet, so classic step-in/step-over both collapse
 * to {@link #step} ("run to the next statement" - loop and if bodies are entered naturally).
 * {@link #stepOut} is defined against loop depth ("run until the current while/for exits"), which
 * generalizes to real call frames if functions are added later.
 *
 * <p>Stop keeps working while paused: {@code Thread.interrupt()} (the existing stop path) wakes
 * the {@code wait()} and is rethrown as {@link ScriptStoppedException}.
 */
public final class DebugController {
    private final Object lock = new Object();

    private volatile Set<Integer> breakpoints = Set.of();
    private volatile int currentLine;
    private volatile boolean paused;

    // All guarded by lock.
    private boolean pauseRequested;
    private boolean stepRequested;
    /** Pause once loop depth drops below this; -1 = no step-out in progress. */
    private int stepOutBelowDepth = -1;
    private int depth;

    /** Worker thread: called before every statement; blocks here while paused. */
    public void onStatement(int line) {
        currentLine = line;
        synchronized (lock) {
            boolean shouldPause = pauseRequested
                    || stepRequested
                    || (stepOutBelowDepth >= 0 && depth < stepOutBelowDepth)
                    || breakpoints.contains(line);
            if (!shouldPause) {
                return;
            }
            pauseRequested = false;
            stepRequested = false;
            stepOutBelowDepth = -1;
            paused = true;
            try {
                while (paused) {
                    lock.wait();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                paused = false;
                throw new ScriptStoppedException();
            }
        }
    }

    /** Worker thread: brackets each while/for loop's whole execution (see Interpreter). */
    public void enterLoop() {
        synchronized (lock) {
            depth++;
        }
    }

    public void exitLoop() {
        synchronized (lock) {
            depth--;
        }
    }

    /** Replaces the breakpoint set; takes effect immediately, mid-run included. */
    public void setBreakpoints(Set<Integer> lines) {
        breakpoints = Set.copyOf(lines);
    }

    /** Pause at the next statement (no-op if already paused). */
    public void requestPause() {
        synchronized (lock) {
            if (!paused) {
                pauseRequested = true;
            }
        }
    }

    public void resume() {
        synchronized (lock) {
            paused = false;
            lock.notifyAll();
        }
    }

    /** While paused: run exactly one statement, then pause again. While running: same as {@link #requestPause}. */
    public void step() {
        synchronized (lock) {
            if (paused) {
                stepRequested = true;
                paused = false;
                lock.notifyAll();
            } else {
                pauseRequested = true;
            }
        }
    }

    /**
     * While paused inside a loop: run until that loop (the innermost one) has exited, then pause
     * at the next statement. Paused at top level (depth 0), this is just {@link #resume} - there
     * is nothing to step out of. No-op while running.
     */
    public void stepOut() {
        synchronized (lock) {
            if (paused) {
                stepOutBelowDepth = depth;
                paused = false;
                lock.notifyAll();
            }
        }
    }

    /** The line of the statement about to run (or running) - meaningful while the script is alive. */
    public int currentLine() {
        return currentLine;
    }

    public boolean isPaused() {
        return paused;
    }

    public Set<Integer> breakpoints() {
        return breakpoints;
    }
}
