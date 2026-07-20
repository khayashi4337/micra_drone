package io.github.khayashi4337.micradrone.drone.sim;

import java.util.ArrayList;
import java.util.List;

import io.github.khayashi4337.micradrone.lang.Interpreter;
import io.github.khayashi4337.micradrone.lang.Lexer;
import io.github.khayashi4337.micradrone.lang.MicraLangException;
import io.github.khayashi4337.micradrone.lang.Parser;
import io.github.khayashi4337.micradrone.lang.ast.Stmt;

/**
 * Runs a script as a dry run on an in-memory plot and returns everything that happened as a
 * replayable {@link SimTrace} - the engine behind the IDE screen's bird's-eye preview. Uses the
 * exact same {@link Lexer}/{@link Parser}/{@link Interpreter} as a real run, just wired to
 * {@link SimDroneApi} instead of the live farm, so language behavior can't diverge. Synchronous
 * and bounded (see SimDroneApi's step cap and Interpreter's runaway watchdog): safe to call
 * directly from a GUI button handler.
 */
public final class ScriptSimulator {
    private ScriptSimulator() {
    }

    public static SimTrace run(String source, int worldSize) {
        List<SimFrame> frames = new ArrayList<>();
        List<SimLogLine> logLines = new ArrayList<>();
        SimDroneApi api = new SimDroneApi(new SimulatedPlot(worldSize), frames, logLines);
        String error = null;
        boolean truncated = false;
        try {
            List<Stmt> program = new Parser(new Lexer(source).scan()).parseProgram();
            api.recordInitialFrame();
            new Interpreter(api).run(program);
        } catch (SimDroneApi.TraceTruncated e) {
            truncated = true;
        } catch (MicraLangException e) {
            error = e.getMessage();
        } catch (RuntimeException e) {
            // Mirrors DroneScriptRunner's catch-all so e.g. move("up")'s IllegalArgumentException
            // surfaces as a readable error instead of crashing the screen.
            error = String.valueOf(e.getMessage());
        }
        return new SimTrace(worldSize, List.copyOf(frames), List.copyOf(logLines), error, truncated);
    }
}
