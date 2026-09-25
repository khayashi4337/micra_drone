package io.github.khayashi4337.micradrone.drone;

import java.util.Optional;

import io.github.khayashi4337.micradrone.chat.BlockSnapshotReader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The server-authoritative counterpart to {@code client.LiveBlockSnapshotReader}: reads the real
 * {@link ServerLevel} instead of the client's own (possibly stale, and in principle spoofable)
 * ClientLevel. Needed for verifying what construction actually produced on the server - the source
 * of truth in multiplayer - rather than what one player's screen happens to show.
 *
 * <p>Not yet wired into any production entry point: this class exists ahead of its first caller
 * (the still-to-be-built Factory Analyzer / construction-verification loop), matching the precedent
 * set by {@link io.github.khayashi4337.micradrone.lang.VirtualScheduler}. Callers must invoke
 * {@link #read} only from the main server thread (the same contract {@link FarmBlockAccess}
 * documents) - this class does not dispatch or synchronize on its own.
 */
public final class ServerBlockSnapshotReader implements BlockSnapshotReader {
    /** Matches {@code client.LiveBlockSnapshotReader.MAX_BLOCKS_PER_QUERY} for consistent behavior between the two readers. */
    static final int MAX_BLOCKS_PER_QUERY = 1000;

    private final ServerLevel level;

    public ServerBlockSnapshotReader(ServerLevel level) {
        this.level = level;
    }

    @Override
    public Optional<String> read(int x1, int y1, int z1, int x2, int y2, int z2) {
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
