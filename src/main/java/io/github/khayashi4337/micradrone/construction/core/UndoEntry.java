package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.Objects;

/** The position to restore and the block that stood there before the job (design 01, section 8). */
public record UndoEntry(IntPos pos, BlockSpec before) {
    public UndoEntry {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(before, "before");
    }
}
