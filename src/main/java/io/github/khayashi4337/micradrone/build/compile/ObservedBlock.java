package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.Objects;

/**
 * What the world actually holds at a position: the block with all its states, and whether a block entity sits there
 * (design 01, section 8). The chunk-loaded state is the runtime's concern (construction.core.WorldCell).
 */
public record ObservedBlock(BlockSpec block, boolean hasBlockEntity, String blockEntityType) {
    public ObservedBlock {
        Objects.requireNonNull(block, "block");
        blockEntityType = Objects.requireNonNullElse(blockEntityType, "");
        if (!hasBlockEntity && !blockEntityType.isEmpty()) {
            throw new IllegalArgumentException("a block-entity type without a block entity: " + blockEntityType);
        }
    }

    public ObservedBlock(BlockSpec block) {
        this(block, false, "");
    }
}
