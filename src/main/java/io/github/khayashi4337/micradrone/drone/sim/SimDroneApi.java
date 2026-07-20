package io.github.khayashi4337.micradrone.drone.sim;

import java.util.List;

import io.github.khayashi4337.micradrone.lang.DroneApi;
import io.github.khayashi4337.micradrone.lang.Interpreter;

/**
 * {@link DroneApi} for dry-run simulation: fully synchronous (no threads, no pacing, no
 * Minecraft), operating on a {@link SimulatedPlot} and recording one {@link SimFrame} per builtin
 * call. Every call - world actions and reads alike - advances the plot's step clock by one, which
 * is what makes can_harvest()-polling loops converge on maturity (see SimulatedPlot's class
 * comment). Recording is capped at {@link #MAX_STEPS} frames; hitting the cap throws
 * {@link TraceTruncated} to unwind the interpreter, which {@link ScriptSimulator} turns into the
 * trace's truncated flag. Loops that call no builtin at all are already stopped by
 * {@link Interpreter}'s own runaway-statement watchdog.
 */
final class SimDroneApi implements DroneApi {
    static final int MAX_STEPS = 5000;

    /** Thrown to unwind {@link Interpreter} once {@link #MAX_STEPS} frames have been recorded. */
    static final class TraceTruncated extends RuntimeException {
    }

    private final SimulatedPlot plot;
    private final List<SimFrame> frames;
    private final List<SimLogLine> logLines;

    SimDroneApi(SimulatedPlot plot, List<SimFrame> frames, List<SimLogLine> logLines) {
        this.plot = plot;
        this.frames = frames;
        this.logLines = logLines;
    }

    /** Frame 0: the untouched plot, so playback starts from the initial state rather than after the first action. */
    void recordInitialFrame() {
        frames.add(new SimFrame("start", true, plot.droneX(), plot.droneY(), plot.snapshotCells()));
    }

    @Override
    public boolean move(String direction) {
        int dx;
        int dy;
        switch (direction) {
            case "north" -> { dx = 0; dy = -1; }
            case "south" -> { dx = 0; dy = 1; }
            case "east" -> { dx = 1; dy = 0; }
            case "west" -> { dx = -1; dy = 0; }
            default -> throw new IllegalArgumentException("unknown direction: '" + direction + "'");
        }
        return step("move(\"" + direction + "\")", plot.tryMove(dx, dy));
    }

    @Override
    public boolean till() {
        return step("till()", plot.tryTill());
    }

    @Override
    public boolean plant(String crop) {
        return step("plant(\"" + crop + "\")", plot.tryPlant(crop));
    }

    @Override
    public boolean harvest() {
        return step("harvest()", plot.tryHarvest());
    }

    @Override
    public boolean canHarvest() {
        return step("can_harvest()", plot.canHarvest());
    }

    @Override
    public boolean isRotten() {
        return step("is_rotten()", plot.isRotten());
    }

    @Override
    public double getPosX() {
        step("get_pos_x()", true);
        return plot.droneX();
    }

    @Override
    public double getPosY() {
        step("get_pos_y()", true);
        return plot.droneY();
    }

    @Override
    public double getWorldSize() {
        step("get_world_size()", true);
        return plot.size();
    }

    @Override
    public double getPoints() {
        step("get_points()", true);
        return plot.totalPoints();
    }

    @Override
    public double getPoints(String crop) {
        step("get_points(\"" + crop + "\")", true);
        return plot.points(crop);
    }

    @Override
    public void print(String text) {
        // Tagged with the index of the frame recorded just below, so playback reveals the line
        // exactly when its print() call plays.
        logLines.add(new SimLogLine(frames.size(), text));
        step("print", true);
    }

    private boolean step(String action, boolean succeeded) {
        plot.advanceStep();
        frames.add(new SimFrame(action, succeeded, plot.droneX(), plot.droneY(), plot.snapshotCells()));
        if (frames.size() > MAX_STEPS) {
            throw new TraceTruncated();
        }
        return succeeded;
    }
}
