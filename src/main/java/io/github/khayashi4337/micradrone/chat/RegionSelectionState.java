package io.github.khayashi4337.micradrone.chat;

import java.util.Optional;

/**
 * Holds the WorldEdit-style left-click/right-click corner pair from RegionPointerItem until the
 * chat panel consumes it. Plain ints rather than BlockPos so this stays off the Minecraft
 * classpath (see PlotGeometry's history for why that matters to the test sourceSet).
 */
public final class RegionSelectionState {
    /** One clicked block corner, in world coordinates. */
    public record Corner(int x, int y, int z) {
    }

    private Integer x1;
    private Integer y1;
    private Integer z1;
    private Integer x2;
    private Integer y2;
    private Integer z2;

    /**
     * Starts a new selection: any previously clicked end corner is dropped, so the in-world
     * preview never shows a box stitched from a fresh start and a stale end (same restart
     * semantics as Create's super-glue selection, which this pointer imitates).
     */
    public void setCorner1(int x, int y, int z) {
        x1 = x;
        y1 = y;
        z1 = z;
        x2 = null;
        y2 = null;
        z2 = null;
    }

    public void setCorner2(int x, int y, int z) {
        x2 = x;
        y2 = y;
        z2 = z;
    }

    /** True once both corners have been clicked (a single block: click the same spot twice). */
    public boolean hasSelection() {
        return x1 != null && x2 != null;
    }

    /** The start corner, if one has been clicked since the last consume/clear. */
    public Optional<Corner> corner1() {
        return x1 == null ? Optional.empty() : Optional.of(new Corner(x1, y1, z1));
    }

    /** The end corner, if one has been clicked after the current start corner. */
    public Optional<Corner> corner2() {
        return x2 == null ? Optional.empty() : Optional.of(new Corner(x2, y2, z2));
    }

    /**
     * Returns the min/max-normalized "(x,y,z)~(x,y,z)" text for the pending selection and clears
     * it - a one-shot read, so reopening the chat panel later without a fresh selection inserts
     * nothing.
     */
    public Optional<String> consumeAsText() {
        if (!hasSelection()) {
            return Optional.empty();
        }
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2);
        int maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);
        String text = "(%d,%d,%d)~(%d,%d,%d)".formatted(minX, minY, minZ, maxX, maxY, maxZ);
        clear();
        return Optional.of(text);
    }

    public void clear() {
        x1 = null;
        y1 = null;
        z1 = null;
        x2 = null;
        y2 = null;
        z2 = null;
    }
}
