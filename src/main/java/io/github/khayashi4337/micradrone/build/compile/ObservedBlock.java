package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.Objects;

/** What the world actually holds at a position. (The chunk-loaded state is the runtime's concern.) */
public record ObservedBlock(BlockSpec block) {
    public ObservedBlock {
        Objects.requireNonNull(block, "block");
    }
}
