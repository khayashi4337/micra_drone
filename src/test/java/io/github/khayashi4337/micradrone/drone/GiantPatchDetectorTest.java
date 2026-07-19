package io.github.khayashi4337.micradrone.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.khayashi4337.micradrone.drone.GiantPatchDetector.Patch;

class GiantPatchDetectorTest {

    private static boolean[][] gridOf(String... rows) {
        boolean[][] grid = new boolean[rows.length][rows[0].length()];
        for (int gx = 0; gx < rows.length; gx++) {
            for (int gy = 0; gy < rows[gx].length(); gy++) {
                grid[gx][gy] = rows[gx].charAt(gy) == 'X';
            }
        }
        return grid;
    }

    @Test
    void findsAFullyMatureThreeByThreeSquare() {
        boolean[][] grid = gridOf(
                "XXX",
                "XXX",
                "XXX");
        Optional<Patch> patch = GiantPatchDetector.findLargestSquare(grid);
        assertEquals(Optional.of(new Patch(3, 0, 0)), patch);
    }

    @Test
    void aSingleMaturePumpkinIsNotAGiantPatch() {
        boolean[][] grid = gridOf(
                "X..",
                "...",
                "...");
        assertEquals(Optional.empty(), GiantPatchDetector.findLargestSquare(grid));
    }

    @Test
    void emptyGridHasNoPatch() {
        boolean[][] grid = gridOf(
                "...",
                "...",
                "...");
        assertEquals(Optional.empty(), GiantPatchDetector.findLargestSquare(grid));
    }

    @Test
    void findsTheLargestSquareEvenWhenNotAnchoredAtTheOrigin() {
        boolean[][] grid = gridOf(
                "X....",
                ".XX..",
                ".XX..",
                "....X");
        Optional<Patch> patch = GiantPatchDetector.findLargestSquare(grid);
        assertEquals(Optional.of(new Patch(2, 1, 1)), patch);
    }

    @Test
    void picksTheLargerOfTwoDisjointSquares() {
        boolean[][] grid = gridOf(
                "XX...",
                "XX...",
                "..XXX",
                "..XXX",
                "..XXX");
        Optional<Patch> patch = GiantPatchDetector.findLargestSquare(grid);
        assertTrue(patch.isPresent());
        assertEquals(3, patch.get().side());
        assertEquals(2, patch.get().originGx());
        assertEquals(2, patch.get().originGy());
    }

    @Test
    void bonusPointsMatchesTheOriginalGamesFormula() {
        assertEquals(8, GiantPatchDetector.bonusPoints(2));
        assertEquals(27, GiantPatchDetector.bonusPoints(3));
        assertEquals(125, GiantPatchDetector.bonusPoints(5));
        assertEquals(216, GiantPatchDetector.bonusPoints(6));
        assertEquals(294, GiantPatchDetector.bonusPoints(7));
    }

    @Test
    void classifyPositionIdentifiesAllFourCornersInAThreeByThreePatch() {
        assertEquals(0, GiantPatchDetector.classifyPosition(0, 0, 3));
        assertEquals(1, GiantPatchDetector.classifyPosition(2, 0, 3));
        assertEquals(2, GiantPatchDetector.classifyPosition(0, 2, 3));
        assertEquals(3, GiantPatchDetector.classifyPosition(2, 2, 3));
        assertEquals(8, GiantPatchDetector.classifyPosition(1, 1, 3));
    }

    @Test
    void classifyPositionTilesTheSameNinePositionsOnALargerPatch() {
        // a 5x5 patch: edges should span the whole side, not just one tile, and still resolve to
        // exactly one of the 4 edge positions (not a corner) away from the actual corners.
        assertEquals(6, GiantPatchDetector.classifyPosition(2, 0, 5)); // north edge, middle column
        assertEquals(7, GiantPatchDetector.classifyPosition(2, 4, 5)); // south edge, middle column
        assertEquals(4, GiantPatchDetector.classifyPosition(0, 2, 5)); // west edge, middle row
        assertEquals(5, GiantPatchDetector.classifyPosition(4, 2, 5)); // east edge, middle row
        assertEquals(8, GiantPatchDetector.classifyPosition(2, 2, 5)); // center
    }
}
