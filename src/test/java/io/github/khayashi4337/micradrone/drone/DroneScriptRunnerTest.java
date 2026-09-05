package io.github.khayashi4337.micradrone.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.khayashi4337.micradrone.lang.Lexer;
import io.github.khayashi4337.micradrone.lang.Parser;
import io.github.khayashi4337.micradrone.lang.ast.Stmt;

/** End-to-end: parsed script -> Interpreter -> LiveDroneApi -> paced main-thread hand-off. */
class DroneScriptRunnerTest {

    private static List<Stmt> parse(String source) {
        return new Parser(new Lexer(source).scan()).parseProgram();
    }

    /** Drives the fake main thread/clock from the calling thread until the script leaves RUNNING. */
    private static void driveClockUntilTerminal(DroneScriptRunner runner, FakeMainThreadGateway gateway,
            PacedActionQueue queue, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        long tick = 0;
        while (runner.getState() == DroneScriptRunner.State.RUNNING) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for script to leave RUNNING, state=" + runner.getState());
            }
            gateway.pump();
            gateway.advanceTo(++tick, queue);
            Thread.sleep(1);
        }
    }

    @Test
    void scriptMovesAndPrintsThenFinishesIdle() throws Exception {
        FakeMainThreadGateway gateway = new FakeMainThreadGateway();
        PacedActionQueue queue = new PacedActionQueue();
        FakeGridState grid = new FakeGridState(5);
        List<String> logs = new ArrayList<>();
        LiveDroneApi api = new LiveDroneApi(gateway, queue, grid, new FakeFarmBlockAccess(), logs::add);
        DroneScriptRunner runner = new DroneScriptRunner(api, logs::add);

        runner.start(parse("""
                move("east")
                move("east")
                print(get_pos_x())
                """));

        driveClockUntilTerminal(runner, gateway, queue, 5000);

        assertEquals(DroneScriptRunner.State.IDLE, runner.getState());
        assertEquals(2, grid.gridX());
        assertEquals(List.of("2"), logs);
    }

    @Test
    void languageErrorSetsErrorStateAndLastError() throws Exception {
        FakeMainThreadGateway gateway = new FakeMainThreadGateway();
        PacedActionQueue queue = new PacedActionQueue();
        FakeGridState grid = new FakeGridState(5);
        List<String> logs = new ArrayList<>();
        LiveDroneApi api = new LiveDroneApi(gateway, queue, grid, new FakeFarmBlockAccess(), logs::add);
        DroneScriptRunner runner = new DroneScriptRunner(api, logs::add);

        runner.start(parse("""
                print(undefined_variable)
                """));

        driveClockUntilTerminal(runner, gateway, queue, 5000);

        assertEquals(DroneScriptRunner.State.ERROR, runner.getState());
        assertTrue(runner.getLastError().contains("undefined_variable"));
    }

    @Test
    void stopInterruptsAnInfiniteMovementLoop() throws Exception {
        FakeMainThreadGateway gateway = new FakeMainThreadGateway();
        PacedActionQueue queue = new PacedActionQueue();
        FakeGridState grid = new FakeGridState(5);
        LiveDroneApi api = new LiveDroneApi(gateway, queue, grid, new FakeFarmBlockAccess(), msg -> {});
        DroneScriptRunner runner = new DroneScriptRunner(api, msg -> {});

        runner.start(parse("""
                while True:
                    move("east")
                """));

        Thread stopper = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            runner.stop();
        });
        stopper.start();

        driveClockUntilTerminal(runner, gateway, queue, 5000);
        stopper.join();

        assertEquals(DroneScriptRunner.State.STOPPED, runner.getState());
    }

    /**
     * MAX_CALL_DEPTH (Interpreter) must trip cleanly before the JVM's own stack limit does, on the
     * exact thread construction {@link #start} actually uses (plain {@code new Thread(...)}, no
     * custom stack size - see docs/design/lang_def_return_break_continue.md's verification
     * requirement). If the counter check were too generous, this would instead fail as a raw
     * StackOverflowError - runProgram's {@code catch (Throwable)} still sets State.ERROR before
     * re-throwing it, but lastError would read "null", not mention recursion, and the re-thrown
     * Error would print an unhandled stack trace from the worker thread.
     */
    @Test
    void tooMuchRecursionOnTheRealWorkerThreadFailsCleanlyInsteadOfStackOverflowError() throws Exception {
        FakeMainThreadGateway gateway = new FakeMainThreadGateway();
        PacedActionQueue queue = new PacedActionQueue();
        FakeGridState grid = new FakeGridState(5);
        LiveDroneApi api = new LiveDroneApi(gateway, queue, grid, new FakeFarmBlockAccess(), msg -> {});
        DroneScriptRunner runner = new DroneScriptRunner(api, msg -> {});

        runner.start(parse("""
                def recurse(n):
                    return recurse(n + 1)
                recurse(0)
                """));

        driveClockUntilTerminal(runner, gateway, queue, 5000);

        assertEquals(DroneScriptRunner.State.ERROR, runner.getState());
        assertTrue(runner.getLastError().contains("recursion"),
                "expected a clean recursion-limit message on the real worker thread, got: " + runner.getLastError());
    }

    /**
     * A heavier per-level shape than the minimal "return recurse(n + 1)" case above: each level
     * builds a list and a dict literal and does some arithmetic before recursing, so several more
     * Java stack frames (eval/evalBinary/evalListLit/evalDictLit/evalCall) pile up per Micra-level
     * call than the minimal case exercises (both Codex and an independent Fable 5.1 review flagged
     * this as the more realistic worst case worth measuring, rather than reasoning about it
     * abstractly). Confirms MAX_CALL_DEPTH still trips cleanly - not a raw StackOverflowError -
     * even under this heavier shape, on the real worker thread construction.
     */
    @Test
    void heavierPerLevelRecursionStillFailsCleanlyOnTheRealWorkerThread() throws Exception {
        FakeMainThreadGateway gateway = new FakeMainThreadGateway();
        PacedActionQueue queue = new PacedActionQueue();
        FakeGridState grid = new FakeGridState(5);
        LiveDroneApi api = new LiveDroneApi(gateway, queue, grid, new FakeFarmBlockAccess(), msg -> {});
        DroneScriptRunner runner = new DroneScriptRunner(api, msg -> {});

        runner.start(parse("""
                def heavy(n):
                    x = [1, 2, 3, n]
                    y = {"a": 1, "b": 2}
                    total = 1 + 2 * 3 - 4 / 2 + len(x) + len(y)
                    if n <= 0:
                        return total
                    return total + heavy(n - 1)
                heavy(300)
                """));

        driveClockUntilTerminal(runner, gateway, queue, 5000);

        assertEquals(DroneScriptRunner.State.ERROR, runner.getState());
        assertTrue(runner.getLastError().contains("recursion"),
                "expected a clean recursion-limit message under a heavier per-level body, got: " + runner.getLastError());
    }

    @Test
    void errorSetsErrorStateBeforeItIsRethrown() {
        FakeMainThreadGateway gateway = new FakeMainThreadGateway();
        PacedActionQueue queue = new PacedActionQueue();
        FakeGridState grid = new FakeGridState(5);
        List<String> errors = new ArrayList<>();
        LiveDroneApi api = new LiveDroneApi(gateway, queue, grid, new FakeFarmBlockAccess(),
                msg -> { throw new AssertionError("print failed"); });
        DroneScriptRunner runner = new DroneScriptRunner(api, errors::add);

        AssertionError error = assertThrows(AssertionError.class,
                () -> runner.runProgram(parse("print(\"hello\")")));

        assertEquals("print failed", error.getMessage());
        assertEquals(DroneScriptRunner.State.ERROR, runner.getState());
        assertEquals("print failed", runner.getLastError());
        assertTrue(errors.getFirst().contains("AssertionError: print failed"));
    }
}
