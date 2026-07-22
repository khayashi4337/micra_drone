package io.github.khayashi4337.micradrone.drone;

import java.util.List;
import java.util.function.Consumer;

import io.github.khayashi4337.micradrone.lang.DebugController;
import io.github.khayashi4337.micradrone.lang.DroneApi;
import io.github.khayashi4337.micradrone.lang.Interpreter;
import io.github.khayashi4337.micradrone.lang.MicraLangException;
import io.github.khayashi4337.micradrone.lang.ScriptStoppedException;
import io.github.khayashi4337.micradrone.lang.ast.Stmt;

/** Owns the single worker thread that runs one drone script at a time. */
public final class DroneScriptRunner {
    public enum State { IDLE, RUNNING, STOPPED, ERROR }

    private final DroneApi api;
    private final Consumer<String> logSink;
    /** Null when running without a debugger (the pre-IDE code paths and most tests). */
    private final DebugController debug;

    private volatile Thread worker;
    private volatile State state = State.IDLE;
    private volatile String lastError;

    public DroneScriptRunner(DroneApi api, Consumer<String> logSink) {
        this(api, logSink, null);
    }

    public DroneScriptRunner(DroneApi api, Consumer<String> logSink, DebugController debug) {
        this.api = api;
        this.logSink = logSink;
        this.debug = debug;
    }

    public synchronized void start(List<Stmt> program) {
        if (state == State.RUNNING) {
            throw new IllegalStateException("a script is already running");
        }
        state = State.RUNNING;
        lastError = null;
        worker = new Thread(() -> runProgram(program), "MicraDrone-Script");
        worker.setDaemon(true);
        worker.start();
    }

    /** Requests the running script to stop. Safe to call even when nothing is running. */
    public synchronized void stop() {
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
    }

    public State getState() {
        return state;
    }

    public String getLastError() {
        return lastError;
    }

    private void runProgram(List<Stmt> program) {
        try {
            new Interpreter(api, debug).run(program);
            state = State.IDLE;
        } catch (ScriptStoppedException e) {
            state = State.STOPPED;
        } catch (MicraLangException e) {
            lastError = e.getMessage();
            logSink.accept("error: " + e.getMessage());
            state = State.ERROR; // set after lastError so a thread observing ERROR always sees the message too
        } catch (RuntimeException e) {
            lastError = String.valueOf(e.getMessage());
            logSink.accept("error: " + e);
            state = State.ERROR; // set after lastError so a thread observing ERROR always sees the message too
        }
    }
}
