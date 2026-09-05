package io.github.khayashi4337.micradrone.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

/**
 * Exercises the full debugger protocol through the real {@link Interpreter} running on a worker
 * thread against {@link FakeDroneApi}, exactly as a live run does (minus Minecraft).
 */
class DebugControllerTest {

    private static final long WAIT_MILLIS = 2000;

    private final FakeDroneApi api = new FakeDroneApi(5);
    private final DebugController debug = new DebugController();
    private final AtomicReference<Throwable> workerError = new AtomicReference<>();

    private Thread startScript(String source) {
        Thread worker = new Thread(() -> {
            try {
                new Interpreter(api, debug).run(new Parser(new Lexer(source).scan()).parseProgram());
            } catch (Throwable e) {
                workerError.set(e);
            }
        }, "debug-test-script");
        worker.setDaemon(true);
        worker.start();
        return worker;
    }

    private static void awaitTrue(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("timed out waiting for: " + what);
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    private static void awaitExit(Thread worker) throws InterruptedException {
        worker.join(WAIT_MILLIS);
        assertFalse(worker.isAlive(), "script thread should have finished");
    }

    @Test
    void breakpointPausesBeforeTheStatementAndResumeCompletes() throws Exception {
        debug.setBreakpoints(Set.of(2));
        Thread worker = startScript("print(\"a\")\nprint(\"b\")\nprint(\"c\")\n");

        awaitTrue("pause at breakpoint", debug::isPaused);
        assertEquals(2, debug.currentLine());
        assertEquals(List.of("a"), api.printed); // paused BEFORE line 2 runs

        debug.resume();
        awaitExit(worker);
        assertEquals(List.of("a", "b", "c"), api.printed);
    }

    @Test
    void stepRunsExactlyOneStatementThenPausesAgain() throws Exception {
        debug.setBreakpoints(Set.of(1));
        Thread worker = startScript("print(\"a\")\nprint(\"b\")\nprint(\"c\")\n");

        awaitTrue("pause at line 1", () -> debug.isPaused() && debug.currentLine() == 1);
        assertEquals(List.of(), api.printed);

        debug.step();
        awaitTrue("pause at line 2", () -> debug.isPaused() && debug.currentLine() == 2);
        assertEquals(List.of("a"), api.printed);

        debug.step();
        awaitTrue("pause at line 3", () -> debug.isPaused() && debug.currentLine() == 3);
        assertEquals(List.of("a", "b"), api.printed);

        debug.resume();
        awaitExit(worker);
        assertEquals(List.of("a", "b", "c"), api.printed);
    }

    @Test
    void stepOutRunsUntilTheCurrentLoopExits() throws Exception {
        String source = """
                for i in range(3):
                    print("in")
                print("done")
                """;
        debug.setBreakpoints(Set.of(2));
        Thread worker = startScript(source);

        awaitTrue("pause inside the loop", () -> debug.isPaused() && debug.currentLine() == 2);
        debug.setBreakpoints(Set.of()); // else the breakpoint re-pauses every iteration
        debug.stepOut();

        awaitTrue("pause after the loop", () -> debug.isPaused() && debug.currentLine() == 3);
        assertEquals(List.of("in", "in", "in"), api.printed); // the loop ran to completion

        debug.resume();
        awaitExit(worker);
        assertEquals(List.of("in", "in", "in", "done"), api.printed);
    }

    /**
     * A function call is bracketed with the same {@link DebugController#enterLoop}/{@link
     * DebugController#exitLoop} pair a loop is (see {@code Interpreter#callFunction}), so Step Out
     * from inside a function body must run the whole call to completion and pause at the
     * statement right after the call site - not at the function's own next statement, and not by
     * exiting any loop the call happened to be inside.
     */
    @Test
    void stepOutRunsUntilTheCurrentFunctionCallReturns() throws Exception {
        String source = """
                def f():
                    print("in f")
                    print("still in f")
                f()
                print("after f")
                """;
        debug.setBreakpoints(Set.of(2)); // the first print() inside f's body
        Thread worker = startScript(source);

        awaitTrue("pause inside the function", () -> debug.isPaused() && debug.currentLine() == 2);
        debug.setBreakpoints(Set.of()); // clear it before stepOut resumes, so it doesn't re-pause first
        debug.stepOut();

        awaitTrue("pause after the call site", () -> debug.isPaused() && debug.currentLine() == 5);
        assertEquals(List.of("in f", "still in f"), api.printed); // the whole function body ran

        debug.resume();
        awaitExit(worker);
        assertEquals(List.of("in f", "still in f", "after f"), api.printed);
    }

    /**
     * The language has no distinct step-over (see DebugController's class doc): stepping from a
     * call site must land on the function's first statement, not skip past the whole call.
     */
    @Test
    void steppingFromACallSiteEntersTheFunctionBodyRatherThanSteppingOverIt() throws Exception {
        String source = """
                def f():
                    print("in f")
                f()
                print("after f")
                """;
        debug.setBreakpoints(Set.of(3)); // "f()"
        Thread worker = startScript(source);

        awaitTrue("pause at the call site", () -> debug.isPaused() && debug.currentLine() == 3);
        debug.setBreakpoints(Set.of());
        debug.step();

        awaitTrue("pause inside the function body", () -> debug.isPaused() && debug.currentLine() == 2);
        assertEquals(List.of(), api.printed); // f()'s own print hasn't run yet

        debug.resume();
        awaitExit(worker);
        assertEquals(List.of("in f", "after f"), api.printed);
    }

    /**
     * The common case: the function call is not the loop body's last statement, so once the call
     * frame's depth drops away there is still more of the same loop iteration left to run.
     */
    @Test
    void stepOutOfAFunctionCalledMidLoopLandsBackInsideThatLoopsBody() throws Exception {
        String source = """
                def f():
                    print("in f")
                for i in range(3):
                    f()
                    print("after call")
                print("done")
                """;
        debug.setBreakpoints(Set.of(2)); // inside f's body
        Thread worker = startScript(source);

        awaitTrue("pause inside the function on the first iteration", () -> debug.isPaused() && debug.currentLine() == 2);
        debug.setBreakpoints(Set.of());
        debug.stepOut();

        awaitTrue("pause right after the call, still inside the loop body", () -> debug.isPaused() && debug.currentLine() == 5);
        assertEquals(List.of("in f"), api.printed);

        debug.resume();
        awaitExit(worker);
        assertEquals(List.of("in f", "after call", "in f", "after call", "in f", "after call", "done"), api.printed);
    }

    /**
     * The edge case DebugController's class doc calls out explicitly: when the call is the loop
     * body's last statement AND this is the loop's last iteration, there is nothing left of that
     * frame OR the loop to return to - the next {@code onStatement} genuinely lands past the whole
     * loop, not "back inside" it. This is what makes the general class-doc wording ("pause at
     * whatever onStatement comes next") the accurate one, not the narrower "back in the loop"
     * framing an earlier draft of that doc used.
     */
    @Test
    void stepOutOfAFunctionCalledOnTheLoopsFinalIterationCanLandPastTheWholeLoop() throws Exception {
        String source = """
                def f():
                    print("in f")
                for i in range(2):
                    f()
                print("done")
                """;
        debug.setBreakpoints(Set.of(2));
        Thread worker = startScript(source);

        awaitTrue("pause inside the function on the first iteration", () -> debug.isPaused() && debug.currentLine() == 2);
        debug.resume(); // let the first call finish; the breakpoint fires again on the second (final) call

        awaitTrue("pause inside the function on the second, final iteration",
                () -> debug.isPaused() && debug.currentLine() == 2);
        debug.setBreakpoints(Set.of());
        debug.stepOut();

        awaitTrue("pause past the whole loop, not back inside it", () -> debug.isPaused() && debug.currentLine() == 5);
        assertEquals(List.of("in f", "in f"), api.printed);

        debug.resume();
        awaitExit(worker);
        assertEquals(List.of("in f", "in f", "done"), api.printed);
    }

    @Test
    void stepOutAtTopLevelSimplyResumes() throws Exception {
        debug.setBreakpoints(Set.of(1));
        Thread worker = startScript("print(\"a\")\nprint(\"b\")\n");

        awaitTrue("pause at line 1", debug::isPaused);
        debug.setBreakpoints(Set.of());
        debug.stepOut(); // depth 0: nothing to step out of - runs to the end

        awaitExit(worker);
        assertEquals(List.of("a", "b"), api.printed);
    }

    @Test
    void interruptWhilePausedStopsTheScript() throws Exception {
        debug.setBreakpoints(Set.of(1));
        Thread worker = startScript("print(\"a\")\n");

        awaitTrue("pause at line 1", debug::isPaused);
        worker.interrupt(); // the existing Stop path
        awaitExit(worker);

        assertInstanceOf(ScriptStoppedException.class, workerError.get());
        assertEquals(List.of(), api.printed);
    }

    @Test
    void pauseRequestPausesAFreeRunningLoop() throws Exception {
        Thread worker = startScript("while True:\n    can_harvest()\n");

        debug.requestPause();
        awaitTrue("pause of the running loop", debug::isPaused);
        assertTrue(debug.currentLine() >= 1);

        worker.interrupt(); // end the (infinite) test script
        awaitExit(worker);
        assertInstanceOf(ScriptStoppedException.class, workerError.get());
    }

    @Test
    void clearedBreakpointsStopPausingMidRun() throws Exception {
        String source = """
                for i in range(5):
                    print("x")
                """;
        debug.setBreakpoints(Set.of(2));
        Thread worker = startScript(source);

        awaitTrue("first pause", debug::isPaused);
        debug.setBreakpoints(Set.of()); // live swap while the script is mid-run
        debug.resume();

        awaitExit(worker); // would time out here if the old breakpoint kept firing
        assertEquals(5, api.printed.size());
    }
}
