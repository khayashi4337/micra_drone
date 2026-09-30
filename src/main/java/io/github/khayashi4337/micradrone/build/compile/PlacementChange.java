package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.Objects;

/**
 * One position both manifests place but differently — a block id, a block property, or a block-entity config
 * all count as a change: the old and the new placement, plus what the old manifest expects to find in the
 * world now ({@code expectedNow}).
 */
public record PlacementChange(Placement old, Placement now, BlockSpec expectedNow) {
    public PlacementChange {
        Objects.requireNonNull(old, "old");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(expectedNow, "expectedNow");
    }
}
