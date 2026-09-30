package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.List;
import java.util.Objects;

/**
 * One position this project holds (design 01, section 11.1 plus the ledger keys a removal must settle):
 * the block now, the block before the project first touched the position, the job whose ledger answers for the
 * current block, that block's ledger key, and the earlier ledger keys the same job wrote at this position
 * (a foundation over its own ground cut: a removal returns the charge and takes the cut ground back).
 */
public record PlacedEntry(BlockSpec placed, BlockSpec before, String jobId, int placementIndex,
                          List<Integer> earlierKeys) {
    public PlacedEntry {
        Objects.requireNonNull(placed, "placed");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(jobId, "jobId");
        earlierKeys = List.copyOf(earlierKeys);
    }

    public PlacedEntry(BlockSpec placed, BlockSpec before, String jobId, int placementIndex) {
        this(placed, before, jobId, placementIndex, List.of());
    }
}
