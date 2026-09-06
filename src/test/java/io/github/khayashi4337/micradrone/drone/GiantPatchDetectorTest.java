package io.github.khayashi4337.micradrone.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.khayashi4337.micradrone.drone.GiantPatchDetector.Patch;
import io.github.khayashi4337.micradrone.drone.GiantPatchDetector.Square;

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

    @Test
    void worldOrientedPositionFlipsWithThePlotDirectionSoCornersStayInWorldTerms() {
        // A south-east plot (dirX=+1, dirZ=+1): local (0,0) really is the north-west corner.
        assertEquals(0, GiantPatchDetector.worldOrientedPosition(0, 0, 3, 1, 1));
        // A north-west plot (dirX=-1, dirZ=-1): local (0,0) sits at the world's south-east.
        assertEquals(3, GiantPatchDetector.worldOrientedPosition(0, 0, 3, -1, -1));
        // Mixed: west-extending, south-extending -> local (0,0) is the north-EAST corner.
        assertEquals(1, GiantPatchDetector.worldOrientedPosition(0, 0, 3, -1, 1));
        // Edges follow the same flip: the lx=0 column is the west edge only when dirX > 0.
        assertEquals(4, GiantPatchDetector.worldOrientedPosition(0, 1, 3, 1, 1));
        assertEquals(5, GiantPatchDetector.worldOrientedPosition(0, 1, 3, -1, 1));
        // The center never moves.
        assertEquals(8, GiantPatchDetector.worldOrientedPosition(1, 1, 3, -1, -1));
    }

    // ---- findAllSquares: the incremental, multi-patch partition ----

    @Test
    void twoDisjointSquaresAreBothFound() {
        List<Patch> patches = GiantPatchDetector.findAllSquares(gridOf(
                "XX...",
                "XX...",
                "..XXX",
                "..XXX",
                "..XXX"));
        assertEquals(List.of(new Patch(3, 2, 2), new Patch(2, 0, 0)), patches);
    }

    @Test
    void aTwoByFourStripIsTwoSeparateTwoByTwosNotOneBigPatch() {
        // The old flood-fill harvest would have read these 8 cells as sqrt(8) ~ 3 -> 27 points.
        List<Patch> patches = GiantPatchDetector.findAllSquares(gridOf(
                "XXXX",
                "XXXX",
                "....",
                "...."));
        assertEquals(List.of(new Patch(2, 0, 0), new Patch(2, 0, 2)), patches); // gridOf: rows are gx, columns gy
    }

    @Test
    void aThreeByThreeThatCompletesAroundAnOlderTwoByTwoWinsAsAWhole() {
        // Every cell of the old 2x2 is ripe (fused cells count as ripe), so the pass sees one 3x3.
        List<Patch> patches = GiantPatchDetector.findAllSquares(gridOf(
                "XXX.",
                "XXX.",
                "XXX.",
                "...."));
        assertEquals(List.of(new Patch(3, 0, 0)), patches);
    }

    @Test
    void anLShapeIsTheBigSquarePlusWhateverSquaresFitInTheRest() {
        // gridOf: rows are gx, columns gy. The 3x3 goes first, then the two 2x2s left in each arm.
        List<Patch> patches = GiantPatchDetector.findAllSquares(gridOf(
                "XXXXX",
                "XXXXX",
                "XXX..",
                "XX...",
                "XX..."));
        assertEquals(List.of(new Patch(3, 0, 0), new Patch(2, 0, 3), new Patch(2, 3, 0)), patches);
    }

    @Test
    void aFullPlotIsOnePatchWorthTheOriginalGamesSixNSquared() {
        boolean[][] full = new boolean[7][7];
        for (boolean[] row : full) java.util.Arrays.fill(row, true);
        List<Patch> patches = GiantPatchDetector.findAllSquares(full);
        assertEquals(List.of(new Patch(7, 0, 0)), patches);
        assertEquals(294, GiantPatchDetector.bonusPoints(7));
    }

    @Test
    void loneRipeCellsAreLeftOutOfEveryPatch() {
        List<Patch> patches = GiantPatchDetector.findAllSquares(gridOf(
                "X.X",
                ".X.",
                "X.X"));
        assertEquals(List.of(), patches);
    }

    // ---- resolveSquare: reading a square back from its POSITION markers ----

    /** rows[z].charAt(x): a POSITION digit, or '.' for a cell with no giant pumpkin. */
    private static GiantPatchDetector.PositionLookup markers(String... rows) {
        return (x, z) -> {
            if (z < 0 || z >= rows.length || x < 0 || x >= rows[z].length()) {
                return GiantPatchDetector.NOT_GIANT;
            }
            char c = rows[z].charAt(x);
            return c == '.' ? GiantPatchDetector.NOT_GIANT : c - '0';
        };
    }

    @Test
    void resolvesTheSameSquareFromAnyOfItsCells() {
        GiantPatchDetector.PositionLookup lookup = markers(
                ".....",
                ".061.",
                ".485.",
                ".273.",
                ".....");
        Square expected = new Square(1, 1, 3);
        assertEquals(Optional.of(expected), GiantPatchDetector.resolveSquare(lookup, 2, 2, 64)); // center
        assertEquals(Optional.of(expected), GiantPatchDetector.resolveSquare(lookup, 1, 1, 64)); // NW corner
        assertEquals(Optional.of(expected), GiantPatchDetector.resolveSquare(lookup, 3, 3, 64)); // SE corner
        assertEquals(Optional.of(expected), GiantPatchDetector.resolveSquare(lookup, 3, 2, 64)); // E edge
    }

    @Test
    void twoTouchingSquaresAreToldApartByTheirBoundaryMarkers() {
        GiantPatchDetector.PositionLookup lookup = markers(
                "0101",
                "2323");
        assertEquals(Optional.of(new Square(0, 0, 2)), GiantPatchDetector.resolveSquare(lookup, 1, 1, 64));
        assertEquals(Optional.of(new Square(2, 0, 2)), GiantPatchDetector.resolveSquare(lookup, 2, 0, 64));
    }

    @Test
    void aSquareWithAHoleOrANonGiantStartIsNotASquare() {
        GiantPatchDetector.PositionLookup holed = markers(
                "061",
                "4.5",
                "273");
        assertEquals(Optional.empty(), GiantPatchDetector.resolveSquare(holed, 0, 0, 64));
        assertEquals(Optional.empty(), GiantPatchDetector.resolveSquare(holed, 1, 1, 64));
        // Markers that never reach a north-west corner (a stray row of north-edge cells).
        assertEquals(Optional.empty(), GiantPatchDetector.resolveSquare(markers("6666"), 2, 0, 64));
    }

    @Test
    void resolveSquareGivesUpAtMaxSideInsteadOfWalkingForever() {
        GiantPatchDetector.PositionLookup endlessWestEdge = (x, z) -> GiantPatchDetector.POS_E;
        assertEquals(Optional.empty(), GiantPatchDetector.resolveSquare(endlessWestEdge, 0, 0, 8));
    }
}
