package io.github.khayashi4337.micradrone.build.analyze;

import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.List;

/**
 * Hands out the cells of a world box in bounded batches (F-21: a range read never scans the whole box in one tick) and
 * collects the class of each cell into the {@link VoxelClassGrid}. Cells come out in the same order
 * {@link VoxelClassGrid#index} stores them.
 */
public final class VoxelGridFiller {
    private final Box box;
    private final int cells;
    private final byte[] classes;
    private final boolean[] filled;
    private final int sy;
    private final int sz;
    private int next;
    private int filledCount;

    public VoxelGridFiller(Box box) {
        long volume = box.volume();
        if (volume > VoxelClassGrid.MAX_CELLS) {
            throw new IllegalArgumentException("a grid holds at most " + VoxelClassGrid.MAX_CELLS + " cells: " + box);
        }
        this.box = box;
        this.cells = (int) volume;
        this.classes = new byte[cells];
        this.filled = new boolean[cells];
        this.sy = box.maxB() - box.minB() + 1;
        this.sz = box.maxC() - box.minC() + 1;
    }

    /** Up to {@code max} still-unread cells, in {@link VoxelClassGrid#index} order. */
    public List<IntPos> nextBatch(int max) {
        List<IntPos> out = new ArrayList<>();
        while (out.size() < max && next < cells) {
            int index = next++;
            int z = index % sz;
            int xy = index / sz;
            int y = xy % sy;
            int x = xy / sy;
            out.add(new IntPos(box.minA() + x, box.minB() + y, box.minC() + z));
        }
        return out;
    }

    public void set(IntPos pos, byte cls) {
        if (!box.contains(pos.x(), pos.y(), pos.z())) {
            throw new IllegalArgumentException("cell " + pos + " is outside the grid's box " + box);
        }
        int index = VoxelClassGrid.index(box, pos);
        if (!filled[index]) {
            filled[index] = true;
            filledCount++;
        }
        classes[index] = cls;
    }

    public boolean done() {
        return filledCount == cells;
    }

    public VoxelClassGrid grid() {
        if (!done()) {
            throw new IllegalStateException("the grid is not finished: " + filledCount + " of " + cells + " cells");
        }
        return new VoxelClassGrid(box, classes);
    }
}
