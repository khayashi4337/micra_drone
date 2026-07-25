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

    /**
     * {@code markerFound} is false only when no marker was found and {@code defaultSize} was used.
     * {@code groundYOffset} is 0 (embedded controller, farmland at the controller's own Y - the
     * original convention) or -1 (surface-mounted controller, farmland one block down) - see
     * {@link #groundYOffset}.
     */
    public record PlotBounds(int worldSize, int dirX, int dirZ, boolean markerFound, int groundYOffset) {}

    @FunctionalInterface
    public interface MarkerLookup {
        /** True if a corner marker block sits at (dx, dy, dz) relative to the controller. */
        boolean isMarkerAt(int dx, int dy, int dz);
    }

    @FunctionalInterface
    public interface GroundLookup {
        /** True if the block at (dx, dy, dz) relative to the controller is dirt-like (soil). */
        boolean isDirtLikeAt(int dx, int dy, int dz);
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
    public static PlotBounds scan(MarkerLookup lookup, GroundLookup groundLookup, int maxDistance, int yTolerance, int defaultSize) {
        for (int i = 1; i <= maxDistance; i++) {
            for (int[] dir : DIAGONAL_DIRECTIONS) {
                for (int dy = -yTolerance; dy <= yTolerance; dy++) {
                    if (lookup.isMarkerAt(dir[0] * i, dy, dir[1] * i)) {
                        return new PlotBounds(Math.max(0, i - 1), dir[0], dir[1], true, groundYOffset(groundLookup, dir[0], dir[1]));
                    }
                }
            }
        }
        return new PlotBounds(defaultSize, 1, 1, false, groundYOffset(groundLookup, 1, 1));
    }

    /**
     * Placement-style detection (2026-07-25 design): the original convention embeds the controller
     * flush with the farmland row (farmland at the controller's own Y); the newer convention stands
     * the controller on top of natural ground, with farmland one block below. Distinguishes the two
     * by checking the first farmable cell (grid cell 0, one diagonal step from the controller - see
     * {@link PlotGeometry#groundOffset}) at the controller's own Y: dirt-like there means the
     * embedded convention already occupies that spot (0, unchanged); anything else - typically open
     * air, since a surface-mounted controller leaves that same-Y cell empty - means farmland is one
     * level down (-1). Stable under normal play (that cell doesn't change on its own), so existing
     * embedded plots keep working with no migration, and new surface plots resolve correctly from
     * their very first scan, before any corner marker is even placed.
     */
    static int groundYOffset(GroundLookup groundLookup, int dirX, int dirZ) {
        int[] firstCell = PlotGeometry.groundOffset(dirX, dirZ, 0, 0);
        return groundLookup.isDirtLikeAt(firstCell[0], 0, firstCell[1]) ? 0 : -1;
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
