package io.github.khayashi4337.micradrone.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.khayashi4337.micradrone.drone.CornerMarkerScan.PlotBounds;

class CornerMarkerScanTest {

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
        PlotBounds bounds = CornerMarkerScan.scan(markersAt(new int[]{3, 0, 3}), 10, 4, 5);
        assertEquals(new PlotBounds(2, 1, 1), bounds);
    }

    // Regression test for the reported bug: natural terrain is rarely perfectly flat, so a marker
    // placed a couple of blocks higher/lower than the controller must still be found.
    @Test
    void findsMarkerWithinYTolerance() {
        PlotBounds bounds = CornerMarkerScan.scan(markersAt(new int[]{3, 2, 3}), 10, 4, 5);
        assertEquals(new PlotBounds(2, 1, 1), bounds);
    }

    // Regression test for the exact scenario 林さん reported: a 3x3 span (marker 2 diagonal steps away,
    // both corner cells included) must leave only the single center cell farmable - not the corners'
    // own rows/columns, and not spilling one cell past the marker.
    @Test
    void threeByThreeSpanLeavesOnlyTheCenterCellFarmable() {
        PlotBounds bounds = CornerMarkerScan.scan(markersAt(new int[]{2, 0, 2}), 10, 4, 5);
        assertEquals(new PlotBounds(1, 1, 1), bounds);
    }

    @Test
    void markerTouchingDiagonallyLeavesNoFarmableCells() {
        PlotBounds bounds = CornerMarkerScan.scan(markersAt(new int[]{1, 0, 1}), 10, 4, 5);
        assertEquals(new PlotBounds(0, 1, 1), bounds);
    }

    @Test
    void doesNotFindMarkerBeyondYTolerance() {
        PlotBounds bounds = CornerMarkerScan.scan(markersAt(new int[]{3, 5, 3}), 10, 4, 5);
        assertEquals(new PlotBounds(5, 1, 1), bounds); // falls back to default
    }

    @Test
    void doesNotFindMarkerOffTheDiagonal() {
        PlotBounds bounds = CornerMarkerScan.scan(markersAt(new int[]{3, 0, 2}), 10, 4, 5);
        assertEquals(new PlotBounds(5, 1, 1), bounds); // falls back to default
    }

    @Test
    void picksTheNearestMatchAcrossAllFourDirections() {
        // a farther south-east marker and a closer north-west marker: nearest wins regardless of direction
        CornerMarkerScan.MarkerLookup lookup = markersAt(new int[]{6, 0, 6}, new int[]{-2, 0, -2});
        PlotBounds bounds = CornerMarkerScan.scan(lookup, 10, 4, 5);
        assertEquals(new PlotBounds(1, -1, -1), bounds);
    }

    @Test
    void identifiesEachOfTheFourDiagonalDirections() {
        assertEquals(new PlotBounds(1, 1, 1), CornerMarkerScan.scan(markersAt(new int[]{2, 0, 2}), 10, 0, 5)); // south-east
        assertEquals(new PlotBounds(1, -1, 1), CornerMarkerScan.scan(markersAt(new int[]{-2, 0, 2}), 10, 0, 5)); // south-west
        assertEquals(new PlotBounds(1, 1, -1), CornerMarkerScan.scan(markersAt(new int[]{2, 0, -2}), 10, 0, 5)); // north-east
        assertEquals(new PlotBounds(1, -1, -1), CornerMarkerScan.scan(markersAt(new int[]{-2, 0, -2}), 10, 0, 5)); // north-west
    }

    @Test
    void fallsBackToDefaultSizeSouthEastWhenNoMarkerFound() {
        PlotBounds bounds = CornerMarkerScan.scan((dx, dy, dz) -> false, 10, 4, 5);
        assertEquals(new PlotBounds(5, 1, 1), bounds);
    }
}
