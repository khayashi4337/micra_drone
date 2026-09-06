package io.github.khayashi4337.micradrone.lang;

import java.util.Set;

/**
 * Debugger control shared between the script's worker thread and the game: breakpoints, pause/
 * resume, statement stepping, and stepping out of the current loop or function call. The worker
 * thread reports in via {@link #onStatement} (called by {@link Interpreter} before every
 * statement) and blocks right there when a pause is due; the other methods are called from the
 * server main thread (via the IDE's debug payloads) and only flip flags / wake the worker.
 * Minecraft-free and self-contained so the whole protocol is unit-testable with plain threads.
 *
 * <p>The language has user-defined functions (see {@link MicraFunction}), but no distinct
 * step-over: classic step-in/step-over both still collapse to {@link #step} ("run to the next
 * statement" - loop bodies, if bodies, and now function calls are all entered naturally, since
 * {@code onStatement} fires the same way regardless of what frame it's called from). {@link
 * #stepOut} is defined against a single depth counter ("pause the next time {@code depth} drops
 * below its value when stepOut was requested") - {@link Interpreter#callFunction} brackets a call
 * with {@link #enterLoop}/{@link #exitLoop} exactly the way a loop does, so this is genuinely "run
 * until the current loop or function call frame has exited", not "the current loop specifically".
 * Where the *next* pause lands after that depends entirely on what statement is next once the
 * frame is gone - typically back inside an enclosing loop's body, but if the call/loop was the
 * last thing in its own enclosing frame too, the next pause can land past that frame as well (it
 * is simply the next {@code onStatement} call, wherever that turns out to be).
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
    /** Pause once {@link #depth} (loop or function-call nesting) drops below this; -1 = no step-out in progress. */
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

    /** Worker thread: brackets each while/for loop's whole execution, and each function call's, too (see Interpreter). */
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
     * While paused inside a loop or a function call: run until that innermost frame has exited,
     * then pause at the next statement. Paused at top level (depth 0), this is just {@link
     * #resume} - there is nothing to step out of. No-op while running.
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
