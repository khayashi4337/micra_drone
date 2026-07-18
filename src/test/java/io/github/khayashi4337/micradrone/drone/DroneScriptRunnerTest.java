package io.github.khayashi4337.micradrone.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
