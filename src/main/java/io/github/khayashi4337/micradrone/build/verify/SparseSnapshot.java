package io.github.khayashi4337.micradrone.build.verify;

import io.github.khayashi4337.micradrone.build.compile.ObservedBlock;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.Map;

/** The blocks read at the manifest's own positions only (design 01, section 8; F-21: memory grows with the positions). */
public record SparseSnapshot(Map<IntPos, ObservedBlock> blocks) {
    public static final int MAX_POSITIONS = 20_000;

    public SparseSnapshot {
        if (blocks.size() > MAX_POSITIONS) {
            throw new IllegalArgumentException("a snapshot holds at most " + MAX_POSITIONS + " positions; read in windows");
        }
        blocks = Map.copyOf(blocks);
    }
}
