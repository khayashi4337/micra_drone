package io.github.khayashi4337.micradrone.client;

import java.util.Optional;
import java.util.concurrent.TimeoutException;

import io.github.khayashi4337.micradrone.chat.BlockSnapshotReader;
import io.github.khayashi4337.micradrone.chat.ClientMainThreadDispatch;
import io.github.khayashi4337.micradrone.chat.MainThreadExecutor;
import io.github.khayashi4337.micradrone.drone.SenseNames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The real get_block_snapshot implementation: reads the player's currently-loaded client world,
 * always via {@link ClientMainThreadDispatch} so BlockSnapshotToolServer's HTTP handler thread
 * never touches ClientLevel directly (Codex review finding).
 */
public final class LiveBlockSnapshotReader implements BlockSnapshotReader {
    /** A generous cap on how many blocks one query may cover, so a huge accidental range can't stall a render frame. */
    static final int MAX_BLOCKS_PER_QUERY = 1000;
    private static final long RENDER_THREAD_TIMEOUT_MS = 2000L;

    private final MainThreadExecutor executor;

    public LiveBlockSnapshotReader(MainThreadExecutor executor) {
        this.executor = executor;
    }

    @Override
    public Optional<String> read(int x1, int y1, int z1, int x2, int y2, int z2) {
        try {
            return ClientMainThreadDispatch.runAndWait(
                    executor, () -> describe(x1, y1, z1, x2, y2, z2), RENDER_THREAD_TIMEOUT_MS);
        } catch (TimeoutException renderThreadUnresponsive) {
            return Optional.empty();
        }
    }

    private Optional<String> describe(int x1, int y1, int z1, int x2, int y2, int z2) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return Optional.empty();
        }

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
