package io.github.khayashi4337.micradrone.drone;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The block-range-to-text logic shared by both {@code chat.BlockSnapshotReader} implementations
 * (client's LiveBlockSnapshotReader and drone's ServerBlockSnapshotReader) - split out after a code
 * review found the two had drifted into a byte-for-byte duplicate of this loop, its bounds cap, and
 * its error text (CLAUDE.md rule against duplicating the same processing pattern across files).
 * Works against the common {@link Level} supertype so either a ClientLevel or a ServerLevel can be
 * passed without the caller needing two copies of this method.
 */
public final class BlockRangeDescription {
    /** Generous cap on how many blocks one query may cover, shared by both readers. */
    public static final int MAX_BLOCKS_PER_QUERY = 1000;

    private BlockRangeDescription() {
    }

    /**
     * A human-readable description of the blocks in the (inclusive) range, or empty if any part of
     * the range isn't currently loaded in {@code level}.
     */
    public static Optional<String> describe(Level level, int x1, int y1, int z1, int x2, int y2, int z2) {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > MAX_BLOCKS_PER_QUERY) {
            return Optional.of("range too large (" + volume + " blocks, max " + MAX_BLOCKS_PER_QUERY
                    + ") - ask about a smaller range");
        }

        StringBuilder sb = new StringBuilder();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.isLoaded(pos)) {
                        return Optional.empty();
                    }
                    BlockState state = level.getBlockState(pos);
                    ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    sb.append('(').append(x).append(',').append(y).append(',').append(z).append(")=")
                            .append(SenseNames.simplify(id.getNamespace(), id.getPath())).append("; ");
                }
            }
        }
        return Optional.of(sb.toString());
    }
}
