package io.github.khayashi4337.micradrone.drone;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.khayashi4337.micradrone.drone.CornerMarkerScan.PlotBounds;

class CornerMarkerScanTest {
    /** Every test below is about marker-finding, not ground-style detection - dirt everywhere keeps groundYOffset at 0. */
    private static final CornerMarkerScan.GroundLookup ALWAYS_DIRT = (dx, dy, dz) -> true;

    /** (dx, dy, dz) triples where a marker sits, relative to the controller. */
    private static CornerMarkerScan.MarkerLookup markersAt(int[]... positions) {
        Set<String> keys = new HashSet<>();
        for (int[] p : positions) {
            keys.add(p[0] + "," + p[1] + "," + p[2]);
        }
        return (dx, dy, dz) -> keys.contains(dx + "," + dy + "," + dz);
    }

    @Test
    void findsMarkerOnExactDiagonalAtSameY() {
        // marker 3 diagonal steps away: a 4x4 span including both corner cells, so a 2x2 interior remains
        PlotBounds bounds = CornerMarkerScan.scan(markersAt(new int[]{3, 0, 3}), ALWAYS_DIRT, 10, 4, 5);
        assertEquals(new PlotBounds(2, 1, 1, true, 0), bounds);
    }

    // Regression test for the reported bug: natural terrain is rarely perfectly flat, so a marker
    // placed a couple of blocks higher/lower than the controller must still be found.
    @Test
    void findsMarkerWithinYTolerance() {
        PlotBounds bounds = CornerMarkerScan.scan(markersAt(new int[]{3, 2, 3}), ALWAYS_DIRT, 10, 4, 5);
        assertEquals(new PlotBounds(2, 1, 1, true, 0), bounds);
    }

    // Regression test for the exact scenario 林さん reported: a 3x3 span (marker 2 diagonal steps away,
    // both corner cells included) must leave only the single center cell farmable - not the corners'
    // own rows/columns, and not spilling one cell past the marker.
    @Test
    void threeByThreeSpanLeavesOnlyTheCenterCellFarmable() {
        PlotBounds bounds = CornerMarkerScan.scan(markersAt(new int[]{2, 0, 2}), ALWAYS_DIRT, 10, 4, 5);
        assertEquals(new PlotBounds(1, 1, 1, true, 0), bounds);
    }

    @Test
    void markerTouchingDiagonallyLeavesNoFarmableCells() {
        PlotBounds bounds = CornerMarkerScan.scan(markersAt(new int[]{1, 0, 1}), ALWAYS_DIRT, 10, 4, 5);
        assertEquals(new PlotBounds(0, 1, 1, true, 0), bounds);
    }

    @Test
    void doesNotFindMarkerBeyondYTolerance() {
        PlotBounds bounds = CornerMarkerScan.scan(markersAt(new int[]{3, 5, 3}), ALWAYS_DIRT, 10, 4, 5);
        assertEquals(new PlotBounds(5, 1, 1, false, 0), bounds); // falls back to default
    }

    @Test
    void doesNotFindMarkerOffTheDiagonal() {
        PlotBounds bounds = CornerMarkerScan.scan(markersAt(new int[]{3, 0, 2}), ALWAYS_DIRT, 10, 4, 5);
        assertEquals(new PlotBounds(5, 1, 1, false, 0), bounds); // falls back to default
    }

    @Test
    void picksTheNearestMatchAcrossAllFourDirections() {
        // a farther south-east marker and a closer north-west marker: nearest wins regardless of direction
        CornerMarkerScan.MarkerLookup lookup = markersAt(new int[]{6, 0, 6}, new int[]{-2, 0, -2});
        PlotBounds bounds = CornerMarkerScan.scan(lookup, ALWAYS_DIRT, 10, 4, 5);
        assertEquals(new PlotBounds(1, -1, -1, true, 0), bounds);
    }

    @Test
    void identifiesEachOfTheFourDiagonalDirections() {
        assertEquals(new PlotBounds(1, 1, 1, true, 0),
                CornerMarkerScan.scan(markersAt(new int[]{2, 0, 2}), ALWAYS_DIRT, 10, 0, 5)); // south-east
        assertEquals(new PlotBounds(1, -1, 1, true, 0),
                CornerMarkerScan.scan(markersAt(new int[]{-2, 0, 2}), ALWAYS_DIRT, 10, 0, 5)); // south-west
        assertEquals(new PlotBounds(1, 1, -1, true, 0),
                CornerMarkerScan.scan(markersAt(new int[]{2, 0, -2}), ALWAYS_DIRT, 10, 0, 5)); // north-east
        assertEquals(new PlotBounds(1, -1, -1, true, 0),
                CornerMarkerScan.scan(markersAt(new int[]{-2, 0, -2}), ALWAYS_DIRT, 10, 0, 5)); // north-west
    }

    @Test
    void fallsBackToDefaultSizeSouthEastWhenNoMarkerFound() {
        PlotBounds bounds = CornerMarkerScan.scan((dx, dy, dz) -> false, ALWAYS_DIRT, 10, 4, 5);
        assertEquals(new PlotBounds(5, 1, 1, false, 0), bounds);
    }

    @Test
    void findNearestMatchReturnsTheRawOffsetOfTheNearestHit() {
        var result = CornerMarkerScan.findNearestMatch(markersAt(new int[]{3, 0, 3}), 10, 4);
        assertTrue(result.isPresent());
        assertArrayEquals(new int[]{3, 0, 3}, result.get());
    }

    @Test
    void findNearestMatchReturnsEmptyWhenNothingIsInRange() {
        assertEquals(java.util.Optional.empty(), CornerMarkerScan.findNearestMatch((dx, dy, dz) -> false, 10, 4));
    }

    // ---- groundYOffset: embedded (dirt at the first farmable cell) vs surface-mounted (anything else) ----

    @Test
    void groundYOffsetIsZeroWhenTheFirstFarmableCellIsDirtLike() {
        assertEquals(0, CornerMarkerScan.groundYOffset((dx, dy, dz) -> true, 1, 1));
    }

    @Test
    void groundYOffsetIsMinusOneWhenTheFirstFarmableCellIsNotDirtLike() {
        assertEquals(-1, CornerMarkerScan.groundYOffset((dx, dy, dz) -> false, 1, 1));
    }

    @Test
    void groundYOffsetChecksTheCorrectCellForEachDiagonalDirection() {
        // The first farmable cell is PlotGeometry.groundOffset(dirX, dirZ, 0, 0) = (dirX, dirZ), at dy=0.
        CornerMarkerScan.GroundLookup onlyDirtAtMinusOneMinusOne =
                (dx, dy, dz) -> dx == -1 && dy == 0 && dz == -1;
        assertEquals(0, CornerMarkerScan.groundYOffset(onlyDirtAtMinusOneMinusOne, -1, -1));
        assertEquals(-1, CornerMarkerScan.groundYOffset(onlyDirtAtMinusOneMinusOne, 1, 1));
    }

    @Test
    void scanIncludesGroundYOffsetFromWhicheverDirectionWasResolved() {
        // Marker found south-east; the ground check must run against the SAME resolved direction (1, 1).
        CornerMarkerScan.GroundLookup dirtOnlyAtSouthEastFirstCell = (dx, dy, dz) -> dx == 1 && dy == 0 && dz == 1;
        PlotBounds bounds = CornerMarkerScan.scan(markersAt(new int[]{3, 0, 3}), dirtOnlyAtSouthEastFirstCell, 10, 4, 5);
        assertEquals(0, bounds.groundYOffset());

        PlotBounds surfaceBounds = CornerMarkerScan.scan(markersAt(new int[]{3, 0, 3}), (dx, dy, dz) -> false, 10, 4, 5);
        assertEquals(-1, surfaceBounds.groundYOffset());
    }
}
