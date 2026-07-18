package io.github.khayashi4337.micradrone.drone;

/**
 * Pure geometry for {@link DroneControllerBlockEntity#scanForCornerMarker}, kept Minecraft-free so
 * the scanning algorithm itself (diagonal enumeration, Y tolerance, size/direction math) can be unit
 * tested without a real world.
 */
final class CornerMarkerScan {
    /** (dx, dz) unit steps for the 4 world-space diagonals: south-east, south-west, north-east, north-west. */
    static final int[][] DIAGONAL_DIRECTIONS = {
            {1, 1},   // south-east (+X, +Z)
            {-1, 1},  // south-west (-X, +Z)
            {1, -1},  // north-east (+X, -Z)
            {-1, -1}, // north-west (-X, -Z)
    };

    record PlotBounds(int worldSize, int dirX, int dirZ) {}

    @FunctionalInterface
    interface MarkerLookup {
        /** True if a corner marker block sits at (dx, dy, dz) relative to the controller. */
        boolean isMarkerAt(int dx, int dy, int dz);
    }

    private CornerMarkerScan() {}

    /**
     * Scans the 4 diagonals (nearest distance first, within {@code yTolerance} of dy=0) for a marker.
     * Returns {@code defaultSize} toward south-east if none is found.
     *
     * <p>The marker's own cell is counted as part of the plot (worldSize = distance + 1, WorldEdit-style
     * inclusive corners), but since the marker block itself occupies that cell, the last row/column
     * will always fail to till - the effectively farmable area is one smaller in each direction than
     * worldSize suggests. This is treated as an acceptable, discoverable Minecraft-native constraint
     * rather than a bug (see project memory: prefer real block behavior over hiding it).
     */
    static PlotBounds scan(MarkerLookup lookup, int maxDistance, int yTolerance, int defaultSize) {
        for (int i = 1; i <= maxDistance; i++) {
            for (int[] dir : DIAGONAL_DIRECTIONS) {
                for (int dy = -yTolerance; dy <= yTolerance; dy++) {
                    if (lookup.isMarkerAt(dir[0] * i, dy, dir[1] * i)) {
                        return new PlotBounds(i + 1, dir[0], dir[1]);
                    }
                }
            }
        }
        return new PlotBounds(defaultSize, 1, 1);
    }
}
