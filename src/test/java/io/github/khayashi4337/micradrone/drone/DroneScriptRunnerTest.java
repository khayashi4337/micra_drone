package io.github.khayashi4337.micradrone.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.khayashi4337.micradrone.lang.InterruptTable;
import io.github.khayashi4337.micradrone.lang.Lexer;
import io.github.khayashi4337.micradrone.lang.Parser;
import io.github.khayashi4337.micradrone.lang.TaskRegistry;
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

    /**
     * DroneControllerBlockEntity.startFreshRun builds a brand new DroneScriptRunner on every Run
     * (see docs/design/lang_rtos_task_foundation.md's "TaskRegistry/InterruptTableの所有者について"),
     * so a create_task task started by one Run must not survive untracked past the next Run - this
     * exercises the exact shared-registry pattern that fix relies on: two DroneScriptRunner
     * instances constructed with the SAME TaskRegistry, the second one's owner tearing down the
     * first's leftover task before starting.
     */
    @Test
    void reRunningTearsDownATaskThePreviousRunLeftBehind() throws Exception {
        FakeMainThreadGateway gateway = new FakeMainThreadGateway();
        PacedActionQueue queue = new PacedActionQueue();
        FakeGridState grid = new FakeGridState(5);
        LiveDroneApi api = new LiveDroneApi(gateway, queue, grid, new FakeFarmBlockAccess(), msg -> {});
        TaskRegistry taskRegistry = new TaskRegistry();
        InterruptTable interruptTable = new InterruptTable();

        DroneScriptRunner firstRunner = new DroneScriptRunner(api, msg -> {}, null, taskRegistry, interruptTable);
        firstRunner.start(parse("""
                gate = semaphore()
                def blocked():
                    gate.wait()
                create_task("leftover", 5, 0, blocked)
                """));
        driveClockUntilTerminal(firstRunner, gateway, queue, 5000);
        assertEquals(DroneScriptRunner.State.IDLE, firstRunner.getState());
        assertFalse(taskRegistry.tryReserveAndBind(taskRegistry.currentGeneration(), "leftover", new Thread()),
                "the leftover task should still hold its name right after the first run");

        // What DroneControllerBlockEntity.startFreshRun does before building the next DroneScriptRunner.
        taskRegistry.stopAll();
        taskRegistry.reopen();
        DroneScriptRunner secondRunner = new DroneScriptRunner(api, msg -> {}, null, taskRegistry, interruptTable);
        secondRunner.start(parse("print(\"second run\")"));
        driveClockUntilTerminal(secondRunner, gateway, queue, 5000);
        assertEquals(DroneScriptRunner.State.IDLE, secondRunner.getState());

        long deadline = System.currentTimeMillis() + 2000;
        boolean freed = false;
        while (System.currentTimeMillis() < deadline) {
            // tryReserveAndBind with a never-started, never-released placeholder is just a "is the
            // name still occupied?" probe here - release it again immediately either way so this
            // polling loop itself never permanently consumes the name.
            if (taskRegistry.tryReserveAndBind(taskRegistry.currentGeneration(), "leftover", new Thread())) {
                taskRegistry.release("leftover");
                freed = true;
                break;
            }
            Thread.sleep(5);
        }
        assertTrue(freed, "the leftover task's name should free up once stopAll() actually stops it");
    }

    /**
     * Regression test for the design review's found bug: {@code blockOn}'s wait used to be a flat
     * 5-second timeout regardless of how long the paced delay itself should take, so
     * {@code sleep_ticks(40)} (nominally 2 real seconds at 20 TPS) would have thrown a
     * RuntimeException at the 5-second mark on any run where the test doesn't immediately advance
     * the clock - real Minecraft ticks at its own pace, so a script legitimately waiting on a
     * longer sleep_ticks() call must not be timed out just because the main thread hasn't gotten to
     * that tick yet. This sleeps the JVM test thread past the OLD 5-second limit (deliberately,
     * this is the one test in the suite allowed to be this slow - it's the only way to prove a
     * flat-timeout regression doesn't fire without literally waiting past where it used to) while
     * the worker thread sits blocked in the real dispatch/blockOn path, then confirms the script is
     * still RUNNING (not ERROR) before finally advancing the clock to let it finish normally.
     */
    @Test
    void sleepTicksSurvivesLongerThanTheOldFlatFiveSecondTimeout() throws Exception {
        FakeMainThreadGateway gateway = new FakeMainThreadGateway();
        PacedActionQueue queue = new PacedActionQueue();
        FakeGridState grid = new FakeGridState(5);
        LiveDroneApi api = new LiveDroneApi(gateway, queue, grid, new FakeFarmBlockAccess(), msg -> {});
        DroneScriptRunner runner = new DroneScriptRunner(api, msg -> {});

        runner.start(parse("sleep_ticks(40)")); // timeoutForTicks(40) = 5s + 40/20 = 7s; old code used a flat 5s

        gateway.awaitQueuedWork(2000);
        gateway.pump(); // submits the paced entry (readyAt = 0 + 40) into the queue; the worker is now blocked in blockOn

        Thread.sleep(6000); // past the OLD 5s limit, still under the NEW 7s one
        assertEquals(DroneScriptRunner.State.RUNNING, runner.getState(),
                "the script should still be waiting, not failed with a timeout, 6s into a sleep_ticks(40) call");

        gateway.advanceTo(40, queue); // let the sleep actually complete
        driveClockUntilTerminal(runner, gateway, queue, 5000);
        assertEquals(DroneScriptRunner.State.IDLE, runner.getState());
    }
}
