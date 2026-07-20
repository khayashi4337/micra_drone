package io.github.khayashi4337.micradrone.drone.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ScriptSimulatorTest {

    private static SimFrame lastFrame(SimTrace trace) {
        return trace.frames().get(trace.frames().size() - 1);
    }

    @Test
    void cleanRunStartsWithAnInitialFrame() {
        SimTrace trace = ScriptSimulator.run("till()\n", 5);
        assertNull(trace.error());
        assertFalse(trace.truncated());
        assertEquals(2, trace.frames().size());
        SimFrame start = trace.frames().get(0);
        assertEquals("start", start.action());
        assertEquals(0, start.droneX());
        assertEquals(0, start.droneY());
        assertEquals(SimFrame.CELL_UNTILLED, start.cells()[0]);
    }

    @Test
    void tillMarksTheCellUnderTheDrone() {
        SimTrace trace = ScriptSimulator.run("till()\n", 5);
        SimFrame after = lastFrame(trace);
        assertTrue(after.succeeded());
        assertEquals(SimFrame.CELL_TILLED, after.cells()[0]);
    }

    @Test
    void moveUpdatesPositionAndOutOfBoundsFails() {
        SimTrace trace = ScriptSimulator.run("move(\"south\")\nmove(\"east\")\nmove(\"west\")\nmove(\"west\")\n", 3);
        List<SimFrame> frames = trace.frames();
        assertEquals(1, frames.get(1).droneY()); // south = +y
        assertEquals(1, frames.get(2).droneX()); // east = +x
        assertEquals(0, frames.get(3).droneX()); // west back to 0
        assertFalse(frames.get(4).succeeded());  // west off the edge fails...
        assertEquals(0, frames.get(4).droneX()); // ...and the drone stays put
    }

    @Test
    void plantNeedsFarmlandFirst() {
        SimTrace trace = ScriptSimulator.run("plant(\"wheat\")\n", 5);
        assertFalse(lastFrame(trace).succeeded());
    }

    @Test
    void cropMaturesAfterEnoughStepsAndHarvestScoresAPoint() {
        // Busy-polls can_harvest(): each poll is one builtin call = one sim step, so the loop
        // terminates once MATURE_AFTER_STEPS steps have elapsed since planting.
        String script = """
                till()
                plant("wheat")
                while not can_harvest():
                    x = 1
                harvest()
                print(get_points())
                """;
        SimTrace trace = ScriptSimulator.run(script, 5);
        assertNull(trace.error());
        assertFalse(trace.truncated());
        assertEquals(SimFrame.CELL_TILLED, lastFrame(trace).cells()[0]); // harvested back to bare farmland
        assertEquals(1, trace.logLines().size());
        assertEquals("1", trace.logLines().get(0).text());
    }

    @Test
    void growingCropShowsAsGrowingThenMature() {
        String script = """
                till()
                plant("wheat")
                while not can_harvest():
                    x = 1
                """;
        SimTrace trace = ScriptSimulator.run(script, 5);
        // Right after planting: growing. On the final can_harvest() poll: mature.
        assertEquals(SimFrame.CELL_WHEAT_GROWING, trace.frames().get(2).cells()[0]);
        assertEquals(SimFrame.CELL_WHEAT_MATURE, lastFrame(trace).cells()[0]);
    }

    @Test
    void unknownCropFailsToPlant() {
        SimTrace trace = ScriptSimulator.run("till()\nplant(\"cactus\")\n", 5);
        assertFalse(lastFrame(trace).succeeded());
    }

    @Test
    void sameScriptReplaysIdentically() {
        // The pumpkin rot roll is seeded, so two runs of the same script must match frame by frame.
        String script = """
                till()
                plant("pumpkin")
                while not can_harvest():
                    x = 1
                print(is_rotten())
                """;
        SimTrace first = ScriptSimulator.run(script, 5);
        SimTrace second = ScriptSimulator.run(script, 5);
        assertNull(first.error());
        assertEquals(first.frames().size(), second.frames().size());
        assertEquals(first.logLines(), second.logLines());
    }

    @Test
    void endlessActionLoopIsTruncatedNotHung() {
        SimTrace trace = ScriptSimulator.run("while True:\n    till()\n", 5);
        assertTrue(trace.truncated());
        assertNull(trace.error());
        assertEquals(SimDroneApi.MAX_STEPS + 1, trace.frames().size()); // +1: the initial frame
    }

    @Test
    void endlessApiFreeLoopBecomesAWatchdogError() {
        SimTrace trace = ScriptSimulator.run("while True:\n    x = 1\n", 5);
        assertFalse(trace.truncated());
        assertNotNull(trace.error());
    }

    @Test
    void syntaxErrorIsReportedWithitsLine() {
        SimTrace trace = ScriptSimulator.run("till()\nif True\n    till()\n", 5);
        assertNotNull(trace.error());
        assertTrue(trace.error().contains("line 2"), "got: " + trace.error());
    }

    @Test
    void runtimeErrorIsReportedWithItsLine() {
        SimTrace trace = ScriptSimulator.run("till()\nblastoff()\n", 5);
        assertNotNull(trace.error());
        assertTrue(trace.error().contains("line 2"), "got: " + trace.error());
    }

    @Test
    void unknownMoveDirectionSurfacesAsErrorNotCrash() {
        SimTrace trace = ScriptSimulator.run("move(\"up\")\n", 5);
        assertNotNull(trace.error());
        assertTrue(trace.error().contains("unknown direction"), "got: " + trace.error());
    }

    @Test
    void rottenPumpkinHarvestClearsCellButScoresNothing() {
        // Plant pumpkins until the seeded roll produces a rotten one, then harvest it: the cell
        // must clear and the pumpkin point total must not grow past the non-rotten harvests.
        String script = """
                for i in range(20):
                    till()
                    plant("pumpkin")
                    while not can_harvest():
                        x = 1
                    if is_rotten():
                        print("rotten at " + "slot")
                    harvest()
                print(get_points("pumpkin"))
                """;
        SimTrace trace = ScriptSimulator.run(script, 5);
        assertNull(trace.error(), "got: " + trace.error());
        List<SimLogLine> logs = trace.logLines();
        // With seed 0 and 20 rolls at 20%, at least one rot is a statistical certainty for this
        // fixed seed - the exact count is pinned by the seed, so assert consistency, not chance:
        long rottenSeen = logs.stream().filter(l -> l.text().startsWith("rotten")).count();
        String total = logs.get(logs.size() - 1).text();
        assertEquals(String.valueOf(20 - rottenSeen), total);
        assertTrue(rottenSeen >= 1, "seed 0 should produce at least one rotten pumpkin in 20 plants");
    }
}
