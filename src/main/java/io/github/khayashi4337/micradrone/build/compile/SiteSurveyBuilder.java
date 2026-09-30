package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.Box;
import java.util.ArrayList;
import java.util.List;

/** Collects a survey column by column over several ticks, so no single tick scans the whole site. */
public final class SiteSurveyBuilder {
    public static final int SURVEY_COLUMNS_PER_TICK = 1_024;

    private final String dimension;
    private final Box box;
    private final int sx;
    private final int sz;
    private final int[][] ys;
    private final String[][] blocks;
    private final boolean[][] water;
    private final boolean[][] tree;
    private int next;
    private int filled;

    public SiteSurveyBuilder(String dimension, Box worldBounds) {
        this.dimension = dimension;
        this.box = worldBounds;
        this.sx = worldBounds.maxA() - worldBounds.minA() + 1;
        this.sz = worldBounds.maxC() - worldBounds.minC() + 1;
        this.ys = new int[sx][sz];
        this.blocks = new String[sx][sz];
        this.water = new boolean[sx][sz];
        this.tree = new boolean[sx][sz];
    }

    /** The box the survey covers; the adapter's column scan needs its vertical bounds. */
    public Box worldBounds() {
        return box;
    }

    public List<int[]> nextColumns(int max) {
        List<int[]> out = new ArrayList<>();
        while (out.size() < max && next < sx * sz) {
            out.add(new int[]{box.minA() + next / sz, box.minC() + next % sz});
            next++;
        }
        return out;
    }

    public void column(int x, int z, int surfaceY, String surfaceBlock, boolean hasWater, boolean hasTree) {
        int i = x - box.minA();
        int j = z - box.minC();
        if (blocks[i][j] == null) {
            filled++;
        }
        ys[i][j] = surfaceY;
        blocks[i][j] = surfaceBlock;
        water[i][j] = hasWater;
        tree[i][j] = hasTree;
    }

    public boolean done() {
        return filled == sx * sz;
    }

    public SiteSurvey build() {
        if (!done()) {
            throw new IllegalStateException("the survey is not finished: " + filled + " of " + sx * sz + " columns");
        }
        return SiteSurvey.of(dimension, box, ys, blocks, water, tree);
    }
}
