package io.github.khayashi4337.micradrone.drone;

import java.util.List;
import java.util.function.Consumer;

import io.github.khayashi4337.micradrone.lang.DebugController;
import io.github.khayashi4337.micradrone.lang.DroneApi;
import io.github.khayashi4337.micradrone.lang.InterruptTable;
import io.github.khayashi4337.micradrone.lang.Interpreter;
import io.github.khayashi4337.micradrone.lang.MicraLangException;
import io.github.khayashi4337.micradrone.lang.ScriptStoppedException;
import io.github.khayashi4337.micradrone.lang.TaskRegistry;
import io.github.khayashi4337.micradrone.lang.ast.Stmt;

/** Owns the single worker thread that runs one drone script at a time. */
public final class DroneScriptRunner {
    public enum State { IDLE, RUNNING, STOPPED, ERROR }

    private final DroneApi api;
    private final Consumer<String> logSink;
    /** Null when running without a debugger (the pre-IDE code paths and most tests). */
    private final DebugController debug;
    private final TaskRegistry taskRegistry;
    private final InterruptTable interruptTable;

    private volatile Thread worker;
    private volatile State state = State.IDLE;
    private volatile String lastError;

    public DroneScriptRunner(DroneApi api, Consumer<String> logSink) {
        this(api, logSink, null);
    }

    public DroneScriptRunner(DroneApi api, Consumer<String> logSink, DebugController debug) {
        this(api, logSink, debug, new TaskRegistry(), new InterruptTable());
    }

    /**
     * Entry point {@link io.github.khayashi4337.micradrone.drone.DroneControllerBlockEntity} uses:
     * it owns the registry/table across Runs (a new DroneScriptRunner is built on every Run, so
     * these can't live here - see docs/design/lang_rtos_task_foundation.md's "TaskRegistry/
     * InterruptTableの所有者について").
     */
    public DroneScriptRunner(DroneApi api, Consumer<String> logSink, DebugController debug,
            TaskRegistry taskRegistry, InterruptTable interruptTable) {
        this.api = api;
        this.logSink = logSink;
        this.debug = debug;
        this.taskRegistry = taskRegistry;
        this.interruptTable = interruptTable;
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

    /** Requests the running script to stop. Safe to call even when nothing is running. Also stops every task this script's create_task calls started - Stop means "everything this script started", not just its own thread. */
    public synchronized void stop() {
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
        taskRegistry.stopAll();
    }

    public State getState() {
        return state;
    }

    public String getLastError() {
        return lastError;
    }

    void runProgram(List<Stmt> program) {
        try {
            new Interpreter(api, debug, taskRegistry, interruptTable).run(program);
            state = State.IDLE;
        } catch (ScriptStoppedException e) {
            state = State.STOPPED;
        } catch (MicraLangException e) {
            lastError = e.getMessage();
            logSink.accept("error: " + e.getMessage());
            state = State.ERROR; // set after lastError so a thread observing ERROR always sees the message too
        } catch (Throwable e) {
            try {
                lastError = String.valueOf(e.getMessage());
                logSink.accept("error: " + e);
            } finally {
                state = State.ERROR;
                if (e instanceof Error error) {
                    throw error;
                }
            }
        }
    }
}
