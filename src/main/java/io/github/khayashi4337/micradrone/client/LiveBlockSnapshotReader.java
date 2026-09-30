package io.github.khayashi4337.micradrone.client;

import java.util.Optional;
import java.util.concurrent.TimeoutException;

import io.github.khayashi4337.micradrone.chat.BlockSnapshotReader;
import io.github.khayashi4337.micradrone.chat.ClientMainThreadDispatch;
import io.github.khayashi4337.micradrone.chat.MainThreadExecutor;
import io.github.khayashi4337.micradrone.drone.BlockRangeDescription;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * The real get_block_snapshot implementation: reads the player's currently-loaded client world,
 * always via {@link ClientMainThreadDispatch} so BlockSnapshotToolServer's HTTP handler thread
 * never touches ClientLevel directly (Codex review finding). The actual range-to-text logic lives
 * in {@link BlockRangeDescription}, shared with the server-authoritative counterpart
 * {@link io.github.khayashi4337.micradrone.drone.ServerBlockSnapshotReader}.
 */
public final class LiveBlockSnapshotReader implements BlockSnapshotReader {
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
        return BlockRangeDescription.describe(level, x1, y1, z1, x2, y2, z2);
    }
}
