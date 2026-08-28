package io.github.khayashi4337.micradrone.drone;

import java.util.Optional;

/**
 * Pure "largest all-true square in a grid" detection, used to find giant-pumpkin-fusion patches (a
 * simplification of the original game's "NxN matured simultaneously with zero deaths" rule: this only
 * looks at which cells are mature *right now*, not growth history - see LiveFarmBlockAccess for why).
 * Zero Minecraft dependency by design (see PlotGeometry's note on why that matters for this project's
 * test sourceSet).
 */
final class GiantPatchDetector {
    record Patch(int side, int originGx, int originGy) {}

    private GiantPatchDetector() {
    }

    /**
     * matured[gx][gy] is true where a mature pumpkin sits at that grid cell. Returns the largest
     * square patch found - side 1 ("a single mature pumpkin") never counts as a fusion, so this only
     * ever returns side >= 2 - or empty if no such square exists. Standard "maximal square" DP.
     */
    static Optional<Patch> findLargestSquare(boolean[][] matured) {
        int size = matured.length;
        if (size == 0) {
            return Optional.empty();
        }
        int[][] dp = new int[size][size];
        int bestSide = 0;
        int bestGx = -1;
        int bestGy = -1;
        for (int gx = 0; gx < size; gx++) {
            for (int gy = 0; gy < size; gy++) {
                if (!matured[gx][gy]) {
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
     * orientation (see {@link #worldOrientedPosition}) the ids read: 0 NW, 1 NE, 2 SW, 3 SE corner;
     * 4 W, 5 E, 6 N, 7 S edge; 8 center - the order GiantPumpkinBlock's blockstate maps its nine
     * top textures by.
     */
    static int classifyPosition(int lx, int ly, int side) {
        boolean atMinX = lx == 0;
        boolean atMaxX = lx == side - 1;
        boolean atMinY = ly == 0;
        boolean atMaxY = ly == side - 1;
        if (atMinX && atMinY) return 0;
        if (atMaxX && atMinY) return 1;
        if (atMinX && atMaxY) return 2;
        if (atMaxX && atMaxY) return 3;
        if (atMinX) return 4;
        if (atMaxX) return 5;
        if (atMinY) return 6;
        if (atMaxY) return 7;
        return 8;
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
}
