package io.github.khayashi4337.micradrone.build.analyze;

import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.Objects;

/** One byte of class per cell of a world box (design 01, section 8; F-21): about 1.5 MB at the most. */
public record VoxelClassGrid(Box worldBox, byte[] classes) {
    public static final int MAX_CELLS = 1_572_864;

    public VoxelClassGrid {
        Objects.requireNonNull(worldBox, "worldBox");
        if (worldBox.volume() > MAX_CELLS || classes.length != worldBox.volume()) {
            throw new IllegalArgumentException("a grid holds exactly the box's cells, at most " + MAX_CELLS);
        }
        classes = classes.clone();
    }

    static int index(Box b, IntPos p) {
        int sy = b.maxB() - b.minB() + 1;
        int sz = b.maxC() - b.minC() + 1;
        return ((p.x() - b.minA()) * sy + (p.y() - b.minB())) * sz + (p.z() - b.minC());
    }

    public byte classAt(IntPos p) {
        return classes[index(worldBox, p)];
    }
}
