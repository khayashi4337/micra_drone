package io.github.khayashi4337.micradrone.drone;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure grid-to-world-offset math for a drone's plot. Deliberately kept in its own class with zero
 * Minecraft dependency - not even indirectly, via merely sharing a class with Minecraft-touching code
 * (Minecraft class references anywhere in a class, even in unrelated methods, can make the whole class
 * fail to verify on the test sourceSet, which has no net.minecraft.* on its runtime classpath - see
 * LiveFarmBlockAccess's history: first a static field, then an instanceof/interface-call pattern, each
 * broke calling this file's methods from tests until moved out here).
 */
final class PlotGeometry {
    private PlotGeometry() {
    }

    /** Returns {dx, dz}: the offset from the controller to grid cell (gx, gy). */
    static int[] groundOffset(int dirX, int dirZ, int gx, int gy) {
        return new int[]{dirX * (1 + gx), dirZ * (1 + gy)};
    }

    /**
     * The controller and its corner marker span a square; these are the offsets (from the
     * controller) of the OTHER two vertices of that square - the designated spots for script
     * library containers (issue #7). {@code markerDx/markerDz} is the marker's offset from the
     * controller. Order is deterministic: the same-X-as-marker corner first.
     */
    static int[][] remainingCornerOffsets(int markerDx, int markerDz) {
        return new int[][]{{markerDx, 0}, {0, markerDz}};
    }

    /** Every {dx, dz} ground offset in a worldSize x worldSize plot, in no particular order. */
    static List<int[]> allGroundOffsets(int dirX, int dirZ, int worldSize) {
        List<int[]> offsets = new ArrayList<>(worldSize * worldSize);
        for (int gx = 0; gx < worldSize; gx++) {
            for (int gy = 0; gy < worldSize; gy++) {
                offsets.add(groundOffset(dirX, dirZ, gx, gy));
            }
        }
        return offsets;
    }
}
