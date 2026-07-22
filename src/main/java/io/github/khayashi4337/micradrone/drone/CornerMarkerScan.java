package io.github.khayashi4337.micradrone.drone;

import java.util.Optional;

/**
 * Pure geometry for {@link DroneControllerBlockEntity#scanForCornerMarker}, kept Minecraft-free so
 * the scanning algorithm itself (diagonal enumeration, Y tolerance, size/direction math) can be unit
 * tested without a real world. Public (not package-private) because the IDE screen runs this same
 * scan against the client-side level to aim its overhead camera (blocks are synced, so it resolves
 * the same plot the server does, with no extra networking).
 */
public final class CornerMarkerScan {
    /** (dx, dz) unit steps for the 4 world-space diagonals: south-east, south-west, north-east, north-west. */
    static final int[][] DIAGONAL_DIRECTIONS = {
            {1, 1},   // south-east (+X, +Z)
            {-1, 1},  // south-west (-X, +Z)
            {1, -1},  // north-east (+X, -Z)
            {-1, -1}, // north-west (-X, -Z)
    };

    /** {@code markerFound} is false only when no marker was found and {@code defaultSize} was used. */
    public record PlotBounds(int worldSize, int dirX, int dirZ, boolean markerFound) {}

    @FunctionalInterface
    public interface MarkerLookup {
        /** True if a corner marker block sits at (dx, dy, dz) relative to the controller. */
        boolean isMarkerAt(int dx, int dy, int dz);
    }

    private CornerMarkerScan() {}

    /**
     * Scans the 4 diagonals (nearest distance first, within {@code yTolerance} of dy=0) for a marker.
     * Returns {@code defaultSize} toward south-east if none is found.
     *
     * <p>Neither corner's own row/column is part of the farmable plot: the controller's is already
     * excluded by construction (grid cell 0 sits one block away from the controller, see
     * {@link LiveFarmBlockAccess#groundPos}), and the marker's row/column is excluded here by using
     * {@code distance - 1} rather than {@code distance} as the plot size. E.g. a marker 2 diagonal
     * steps away (a 3x3 span including both corner cells) leaves only the single center cell farmable.
     * A marker only 1 step away (touching diagonally) leaves 0 farmable cells.
     */
    public static PlotBounds scan(MarkerLookup lookup, int maxDistance, int yTolerance, int defaultSize) {
        for (int i = 1; i <= maxDistance; i++) {
            for (int[] dir : DIAGONAL_DIRECTIONS) {
                for (int dy = -yTolerance; dy <= yTolerance; dy++) {
                    if (lookup.isMarkerAt(dir[0] * i, dy, dir[1] * i)) {
                        return new PlotBounds(Math.max(0, i - 1), dir[0], dir[1], true);
                    }
                }
            }
        }
        return new PlotBounds(defaultSize, 1, 1, false);
    }

    /**
     * Like {@link #scan}, but for the reverse lookup (given a corner marker, find its paired
     * controller): returns the raw {@code {dx, dy, dz}} offset of the nearest match, or empty if
     * nothing was found within range. Used by the Shop screen, opened by right-clicking a corner
     * marker, to resolve which controller's points/unlocks it should operate on.
     */
    public static Optional<int[]> findNearestMatch(MarkerLookup lookup, int maxDistance, int yTolerance) {
        for (int i = 1; i <= maxDistance; i++) {
            for (int[] dir : DIAGONAL_DIRECTIONS) {
                for (int dy = -yTolerance; dy <= yTolerance; dy++) {
                    if (lookup.isMarkerAt(dir[0] * i, dy, dir[1] * i)) {
                        return Optional.of(new int[]{dir[0] * i, dy, dir[1] * i});
                    }
                }
            }
        }
        return Optional.empty();
    }
}
