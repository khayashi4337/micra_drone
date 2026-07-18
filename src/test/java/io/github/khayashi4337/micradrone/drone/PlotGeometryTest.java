package io.github.khayashi4337.micradrone.drone;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.khayashi4337.micradrone.drone.CornerMarkerScan.PlotBounds;

/**
 * End-to-end geometry: CornerMarkerScan (decides plot size/direction) combined with
 * LiveFarmBlockAccess.groundOffset (maps a grid cell to an offset from the controller), checked
 * against 林さん's exact reported scenario for all 4 diagonal directions - not just south-east.
 * Deliberately Minecraft-free (plain int offsets, no BlockPos): the test sourceSet has no access to
 * net.minecraft.* classes in this project's Gradle setup.
 */
class PlotGeometryTest {
    private record Case(String label, int markerDx, int markerDz) {}

    /** Marker 2 diagonal steps away in each of the 4 directions: 林さん's "3x3 -> center only" example. */
    private static List<Case> threeByThreeCases() {
        return List.of(
                new Case("south-east", 2, 2),
                new Case("south-west", -2, 2),
                new Case("north-east", 2, -2),
                new Case("north-west", -2, -2));
    }

    @Test
    void allFourDirectionsLeaveExactlyTheCenterCellFarmable() {
        for (Case c : threeByThreeCases()) {
            CornerMarkerScan.MarkerLookup lookup = (dx, dy, dz) -> dx == c.markerDx() && dy == 0 && dz == c.markerDz();
            PlotBounds bounds = CornerMarkerScan.scan(lookup, 10, 4, 5);

            assertEquals(1, bounds.worldSize(), c.label() + ": expected a single farmable cell");
            assertEquals(c.markerDx() > 0 ? 1 : -1, bounds.dirX(), c.label() + ": wrong dirX");
            assertEquals(c.markerDz() > 0 ? 1 : -1, bounds.dirZ(), c.label() + ": wrong dirZ");

            int[] onlyCellOffset = LiveFarmBlockAccess.groundOffset(bounds.dirX(), bounds.dirZ(), 0, 0);
            int[] expectedOffset = {c.markerDx() > 0 ? 1 : -1, c.markerDz() > 0 ? 1 : -1};
            assertArrayEquals(expectedOffset, onlyCellOffset, c.label() + ": wrong offset for the only farmable cell");
        }
    }

    @Test
    void noFarmableCellEverReachesOrPassesTheMarkerInAnyDirection() {
        for (Case c : threeByThreeCases()) {
            CornerMarkerScan.MarkerLookup lookup = (dx, dy, dz) -> dx == c.markerDx() && dy == 0 && dz == c.markerDz();
            PlotBounds bounds = CornerMarkerScan.scan(lookup, 10, 4, 5);

            for (int gx = 0; gx < bounds.worldSize(); gx++) {
                for (int gy = 0; gy < bounds.worldSize(); gy++) {
                    int[] cell = LiveFarmBlockAccess.groundOffset(bounds.dirX(), bounds.dirZ(), gx, gy);
                    boolean isController = cell[0] == 0 && cell[1] == 0;
                    boolean isMarker = cell[0] == c.markerDx() && cell[1] == c.markerDz();
                    boolean pastMarker = Math.abs(cell[0]) > Math.abs(c.markerDx()) || Math.abs(cell[1]) > Math.abs(c.markerDz());
                    assertFalse(isController, c.label() + ": farmable cell overlaps the controller");
                    assertFalse(isMarker, c.label() + ": farmable cell overlaps the marker");
                    assertFalse(pastMarker, c.label() + ": farmable cell spills past the marker");
                }
            }
        }
    }

    @Test
    void largerSpanLeavesACorrectlyShrunkenInteriorInAllDirections() {
        // marker 4 diagonal steps away (a 5x5 span including both corner cells) -> 3x3 interior
        for (Case c : List.of(new Case("south-east", 4, 4), new Case("north-west", -4, -4))) {
            CornerMarkerScan.MarkerLookup lookup = (dx, dy, dz) -> dx == c.markerDx() && dy == 0 && dz == c.markerDz();
            PlotBounds bounds = CornerMarkerScan.scan(lookup, 10, 4, 5);
            assertEquals(3, bounds.worldSize(), c.label());
        }
    }
}
