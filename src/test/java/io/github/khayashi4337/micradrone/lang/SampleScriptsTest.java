package io.github.khayashi4337.micradrone.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.khayashi4337.micradrone.drone.SampleScripts;
import io.github.khayashi4337.micradrone.lang.ast.Stmt;

/**
 * Runs every bundled sample script (SampleScripts, seeded into new controllers' script folders)
 * against the fake drone so a broken hand-written sample never ships. Catches parse errors, runtime
 * type errors (e.g. concatenating a string with a number), and infinite loops.
 */
class SampleScriptsTest {

    private static List<Stmt> parse(String source) {
        return new Parser(new Lexer(source).scan()).parseProgram();
    }

    @Test
    void everySampleParsesAndRunsWithoutError() {
        for (String source : SampleScripts.ALL.values()) {
            FakeDroneApi api = new FakeDroneApi(3);
            new Interpreter(api).run(parse(source));
        }
    }

    @Test
    void tillAndPlantCoversEveryCellInThePlot() {
        FakeDroneApi api = new FakeDroneApi(3);
        new Interpreter(api).run(parse(SampleScripts.TILL_AND_PLANT));

        long tillCount = api.calls.stream().filter("till"::equals).count();
        long plantCount = api.calls.stream().filter("plant:wheat"::equals).count();
        assertEquals(9, tillCount, "expected one till() per cell in a 3x3 plot");
        assertEquals(9, plantCount, "expected one plant() per cell in a 3x3 plot");
    }

    @Test
    void harvestWhenReadyHarvestsEveryMatureCell() {
        FakeDroneApi api = new FakeDroneApi(3);
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                api.setCropAge(x, y, 3); // mature everywhere
            }
        }

        new Interpreter(api).run(parse(SampleScripts.HARVEST_WHEN_READY));

        long harvestCount = api.calls.stream().filter("harvest"::equals).count();
        assertEquals(9, harvestCount, "expected every one of the 9 mature cells to be harvested");
        assertTrue(api.printed.contains("9"), "expected the printed harvested-cell count to be 9");
    }

    @Test
    void moveSquareReturnsToTheStartingCell() {
        FakeDroneApi api = new FakeDroneApi(3);
        new Interpreter(api).run(parse(SampleScripts.MOVE_SQUARE));

        assertEquals(0, api.posXInt());
        assertEquals(0, api.posYInt());
    }

    @Test
    void pumpkinSmartHarvestSkipsWastedHarvestCallsOnRottenCells() {
        FakeDroneApi api = new FakeDroneApi(3);
        api.setCropAge(0, 0, 3); // mature, ready to harvest normally
        api.setRotten(1, 0, true); // rotten - must be replanted, never harvested

        new Interpreter(api).run(parse(SampleScripts.PUMPKIN_SMART_HARVEST));

        long harvestCount = api.calls.stream().filter("harvest"::equals).count();
        long plantCount = api.calls.stream().filter("plant:pumpkin"::equals).count();
        assertEquals(1, harvestCount, "the rotten cell must not trigger a wasted harvest() call");
        assertEquals(9, plantCount, "expected a plant(\"pumpkin\") attempt on every one of the 9 cells");
        assertEquals(List.of(
                "good pumpkins harvested:", "1",
                "rotten pumpkins skipped (replanted without wasting harvest):", "1",
                "Pumpkin points:", "0"
        ), api.printed);
    }
}
