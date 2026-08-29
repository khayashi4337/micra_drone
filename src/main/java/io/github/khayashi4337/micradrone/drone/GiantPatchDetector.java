package io.github.khayashi4337.micradrone.drone;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pure giant-pumpkin geometry: which squares a grid of ripe pumpkins fuses into, and - going the
 * other way - which square a given fused cell belongs to, read back from the POSITION markers the
 * fusion painted. Zero Minecraft dependency by design (see PlotGeometry's note on why that matters
 * for this project's test sourceSet).
 *
 * <p>Fusion follows the original game's rule "when all the pumpkins in a square are fully grown,
 * they grow together" incrementally: every pass re-partitions the currently ripe cells (already
 * fused ones included) into squares, largest first, so a 2x2 patch grows into a 3x3 as its
 * neighbours ripen instead of staying frozen at whatever fused first. Growth history is not
 * tracked (a deliberate simplification of "matured simultaneously").
 */
final class GiantPatchDetector {
    /** A fused square in grid coordinates: {@code side} cells on a side from its lowest-index corner. */
    record Patch(int side, int originGx, int originGy) {}

    /** A fused square in world coordinates (x east, z south), as read back from POSITION markers. */
    record Square(int originX, int originZ, int side) {}

    /** Where a cell sits inside its patch (GiantPumpkinBlock's POSITION), in world orientation. */
    static final int POS_NW = 0;
    static final int POS_NE = 1;
    static final int POS_SW = 2;
    static final int POS_SE = 3;
    static final int POS_W = 4;
    static final int POS_E = 5;
    static final int POS_N = 6;
    static final int POS_S = 7;
    static final int POS_CENTER = 8;
    /** What {@link PositionLookup} answers for a cell that holds no giant-pumpkin block at all. */
    static final int NOT_GIANT = -1;

    /** Reads the POSITION of the giant-pumpkin block at world (x, z), or {@link #NOT_GIANT}. */
    interface PositionLookup {
        int positionAt(int x, int z);
    }

    private GiantPatchDetector() {
    }

    /**
     * ripe[gx][gy] is true where a ripe pumpkin (or an already fused cell) sits at that grid cell.
     * Returns the largest square found - side 1 ("a single ripe pumpkin") never counts as a fusion,
     * so this only ever returns side >= 2 - or empty if no such square exists. Standard "maximal
     * square" DP; ties go to the square met first in gx-major scan order, which keeps the result
     * deterministic pass to pass.
     */
    static Optional<Patch> findLargestSquare(boolean[][] ripe) {
        int size = ripe.length;
        if (size == 0) {
            return Optional.empty();
        }
        int[][] dp = new int[size][size];
        int bestSide = 0;
        int bestGx = -1;
        int bestGy = -1;
        for (int gx = 0; gx < size; gx++) {
            for (int gy = 0; gy < size; gy++) {
                if (!ripe[gx][gy]) {
                    continue;
                }
                dp[gx][gy] = (gx == 0 || gy == 0) ? 1 : Math.min(dp[gx - 1][gy], Math.min(dp[gx][gy - 1], dp[gx - 1][gy - 1])) + 1;
                if (dp[gx][gy] > bestSide) {
                    bestSide = dp[gx][gy];
                    bestGx = gx - bestSide + 1;
                    bestGy = gy - bestSide + 1;
                }
            }
        }
        if (bestSide < 2) {
            return Optional.empty();
        }
        return Optional.of(new Patch(bestSide, bestGx, bestGy));
    }

    /**
     * Partitions the ripe cells into fused squares, greedily largest first (each square is carved
     * out and the search repeats on what is left), so two 2x2 patches side by side stay two 2x2s
     * (8 + 8 points) rather than being mistaken for something bigger, and a 3x3 that has just
     * completed around an older 2x2 takes precedence over it. Cells no square covers stay single
     * ripe pumpkins.
     */
    static List<Patch> findAllSquares(boolean[][] ripe) {
        boolean[][] remaining = new boolean[ripe.length][];
        for (int gx = 0; gx < ripe.length; gx++) {
            remaining[gx] = ripe[gx].clone();
        }
        List<Patch> patches = new ArrayList<>();
        while (true) {
            Optional<Patch> next = findLargestSquare(remaining);
            if (next.isEmpty()) {
                return patches;
            }
            Patch patch = next.get();
            patches.add(patch);
            for (int lx = 0; lx < patch.side(); lx++) {
                for (int ly = 0; ly < patch.side(); ly++) {
                    remaining[patch.originGx() + lx][patch.originGy() + ly] = false;
                }
            }
        }
    }

    /** Bonus points for harvesting a whole giant patch of the given side length, matching the original game's formula. */
    static long bonusPoints(int side) {
        if (side <= 5) {
            return (long) side * side * side;
        }
        return (long) side * side * 6;
    }

    /**
     * Which of the 9 giant-pumpkin block variants a cell at (lx, ly) within a side x side patch
     * should show: 4 corners, 4 edges, 1 center - tileable, so patches bigger than 3x3 reuse the same
     * 9 positions rather than needing one variant per possible patch size. With lx/ly in world
     * orientation (see {@link #worldOrientedPosition}) the ids are the POS_* constants: minX is
     * west, minY is north. GiantPumpkinBlock's blockstate maps its nine top textures by them.
     */
    static int classifyPosition(int lx, int ly, int side) {
        boolean atMinX = lx == 0;
        boolean atMaxX = lx == side - 1;
        boolean atMinY = ly == 0;
        boolean atMaxY = ly == side - 1;
        if (atMinX && atMinY) return POS_NW;
        if (atMaxX && atMinY) return POS_NE;
        if (atMinX && atMaxY) return POS_SW;
        if (atMaxX && atMaxY) return POS_SE;
        if (atMinX) return POS_W;
        if (atMaxX) return POS_E;
        if (atMinY) return POS_N;
        if (atMaxY) return POS_S;
        return POS_CENTER;
    }

    /**
     * {@link #classifyPosition} for a cell at patch-local grid coords (lx, ly), corrected for the
     * plot's direction: grid x runs along world X times dirX and grid y along world Z times dirZ
     * (see PlotGeometry#groundOffset), so a plot that extends west/north from its controller has its
     * lx = 0 column on the *east* side. The textures are drawn in world terms (a north-west corner,
     * a north edge), so the position must be too - otherwise a west-facing plot's rounded corners
     * would point into the patch.
     */
    static int worldOrientedPosition(int lx, int ly, int side, int dirX, int dirZ) {
        int wx = dirX > 0 ? lx : side - 1 - lx;
        int wz = dirZ > 0 ? ly : side - 1 - ly;
        return classifyPosition(wx, wz, side);
    }

    private static boolean onWestBoundary(int position) {
        return position == POS_NW || position == POS_SW || position == POS_W;
    }

    private static boolean onNorthBoundary(int position) {
        return position == POS_NW || position == POS_NE || position == POS_N;
    }

    /**
     * Reads back which fused square the giant-pumpkin cell at world (x, z) belongs to, purely from
     * the POSITION markers: walk west to the square's west boundary, north to its north-west
     * corner, then east along the north edge to find the side. Because the markers say which
     * boundary a cell is on, two squares touching each other are told apart - a flood fill over
     * "is giant" could not do that. Returns empty if the markers don't describe a consistent square
     * of at most {@code maxSide} (something outside the mod changed the blocks), so the caller can
     * fall back to treating the cell as a lone pumpkin.
     */
    static Optional<Square> resolveSquare(PositionLookup lookup, int x, int z, int maxSide) {
        if (lookup.positionAt(x, z) == NOT_GIANT) {
            return Optional.empty();
        }
        int westX = x;
        for (int steps = 0; !onWestBoundary(lookup.positionAt(westX, z)); steps++) {
            westX--;
            if (steps >= maxSide || lookup.positionAt(westX, z) == NOT_GIANT) {
                return Optional.empty();
            }
        }
        int northZ = z;
        for (int steps = 0; !onNorthBoundary(lookup.positionAt(westX, northZ)); steps++) {
            northZ--;
            if (steps >= maxSide || lookup.positionAt(westX, northZ) == NOT_GIANT) {
                return Optional.empty();
            }
        }
        if (lookup.positionAt(westX, northZ) != POS_NW) {
            return Optional.empty();
        }
        int side = 1;
        while (lookup.positionAt(westX + side - 1, northZ) != POS_NE) {
            if (side > maxSide || lookup.positionAt(westX + side, northZ) == NOT_GIANT) {
                return Optional.empty();
            }
            side++;
        }
        if (side < 2) {
            return Optional.empty();
        }
        for (int lx = 0; lx < side; lx++) {
            for (int lz = 0; lz < side; lz++) {
                if (lookup.positionAt(westX + lx, northZ + lz) != classifyPosition(lx, lz, side)) {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(new Square(westX, northZ, side));
    }
}
