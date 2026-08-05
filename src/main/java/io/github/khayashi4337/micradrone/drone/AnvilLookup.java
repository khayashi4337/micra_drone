package io.github.khayashi4337.micradrone.drone;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;

/**
 * Where is_anvil()/get_repair_cost()/repair_rod() looks for a real anvil (see
 * {@link DroneControllerBlockEntity}) - any of the controller's 6 adjacent faces, the same simple
 * "touching the controller" rule {@link RodStock} uses for the rod stock chest, rather than anything
 * tied to the plot's shape or size.
 */
final class AnvilLookup {
    private AnvilLookup() {
    }

    /** The first adjacent block that's a real anvil (any damage state - {@link BlockTags#ANVIL} covers all three). */
    static Optional<BlockPos> findAnvil(ServerLevel level, BlockPos controllerPos) {
        for (Direction direction : Direction.values()) {
            BlockPos candidate = controllerPos.relative(direction);
            if (level.getBlockState(candidate).is(BlockTags.ANVIL)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
