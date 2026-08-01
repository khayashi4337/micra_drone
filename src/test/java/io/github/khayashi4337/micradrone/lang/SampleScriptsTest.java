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

    /** The beginner sample must stay harmless: one harvest attempt and five flips, nothing that alters the plot. */
    @Test
    void firstProgramHarvestsOnceThenFlipsFiveTimes() {
        FakeDroneApi api = new FakeDroneApi(3);
        api.setCropAge(0, 0, 3); // mature, so the harvest() actually does something

        new Interpreter(api).run(parse(SampleScripts.FIRST_PROGRAM));

        assertEquals(List.of("harvest", "do_a_flip", "do_a_flip", "do_a_flip", "do_a_flip", "do_a_flip"),
                api.calls);
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
    void signalHarvestReadyTurnsOutputOnWhenSomethingIsMatureAndNeverHarvests() {
        FakeDroneApi api = new FakeDroneApi(3);
        api.setCropAge(2, 2, 3); // exactly one mature cell, the last one the snake path visits

        new Interpreter(api).run(parse(SampleScripts.SIGNAL_HARVEST_READY));

        assertEquals(0, api.calls.stream().filter("harvest"::equals).count(), "must never harvest - read-only");
        assertEquals(List.of("set_output:true"),
                api.calls.stream().filter(c -> c.startsWith("set_output")).toList());
        assertEquals(List.of("harvest ready:", "True"), api.printed);
    }

    @Test
    void signalHarvestReadyTurnsOutputOffWhenNothingIsMature() {
        FakeDroneApi api = new FakeDroneApi(3); // fresh plot, nothing planted anywhere

        new Interpreter(api).run(parse(SampleScripts.SIGNAL_HARVEST_READY));

        assertEquals(List.of("set_output:false"),
                api.calls.stream().filter(c -> c.startsWith("set_output")).toList());
        assertEquals(List.of("harvest ready:", "False"), api.printed);
    }

    /**
     * The perception sample (issue #10) must actually branch on what it reads, not just call the
     * new commands: on an untilled plot every cell should take the "dirt -> till, then plant" path.
     */
    @Test
    void surveyPlotTillsEveryUntilledCellItFindsAndPlantsIt() {
        FakeDroneApi api = new FakeDroneApi(3);

        new Interpreter(api).run(parse(SampleScripts.SURVEY_PLOT));

        long tillCount = api.calls.stream().filter("till"::equals).count();
        long plantCount = api.calls.stream().filter("plant:wheat"::equals).count();
        assertEquals(9, tillCount, "every cell of a 3x3 plot starts as dirt and should be tilled");
        assertEquals(9, plantCount, "every tilled cell should then be planted");
        assertTrue(api.printed.contains("plains"), "expected the surveyed biome to be reported");
        assertTrue(api.printed.contains("9"), "expected all 9 cells to be counted as planted");
    }

    /** The collections sample: an untilled 3x3 plot is dirt everywhere, so it must find exactly one kind, 9 times. */
    @Test
    void countGroundTalliesEveryCellByGroundType() {
        FakeDroneApi api = new FakeDroneApi(3);

        new Interpreter(api).run(parse(SampleScripts.COUNT_GROUND));

        assertEquals(List.of("見つけた地面の種類の数:", "1", "dirt", "9"), api.printed);
    }

    @Test
    void plotIdPrintsWhateverTheApiReportsForTheMarker() {
        FakeDroneApi api = new FakeDroneApi(3);
        api.setPlotId("north_field");

        new Interpreter(api).run(parse(SampleScripts.PLOT_ID));

        assertEquals(List.of("plot id:", "north_field"), api.printed);
    }

    @Test
    void moveSquareReturnsToTheStartingCell() {
        FakeDroneApi api = new FakeDroneApi(3);
        new Interpreter(api).run(parse(SampleScripts.MOVE_SQUARE));

        assertEquals(0, api.posXInt());
        assertEquals(0, api.posYInt());
    }

    @Test
    void carrotFarmTillsHarvestsAndReplantsEveryCell() {
        FakeDroneApi api = new FakeDroneApi(3);
        api.setCropAge(0, 0, 3); // mature, ready to harvest

        new Interpreter(api).run(parse(SampleScripts.CARROT_FARM));

        long harvestCount = api.calls.stream().filter("harvest"::equals).count();
        long plantCount = api.calls.stream().filter("plant:carrot"::equals).count();
        assertEquals(1, harvestCount, "expected the one mature cell to be harvested");
        assertEquals(9, plantCount, "expected a plant(\"carrot\") attempt on every one of the 9 cells");
        assertEquals(List.of("carrots harvested:", "1", "Carrot points:", "0"), api.printed);
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
