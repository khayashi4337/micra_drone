package io.github.khayashi4337.micradrone.drone;

import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import io.github.khayashi4337.micradrone.chat.BlockSnapshotReader;
import io.github.khayashi4337.micradrone.chat.ClientMainThreadDispatch;
import net.minecraft.server.level.ServerLevel;

/**
 * The server-authoritative counterpart to {@code client.LiveBlockSnapshotReader}: reads the real
 * {@link ServerLevel} instead of the client's own (possibly stale, and in principle spoofable)
 * ClientLevel. Needed for verifying what construction actually produced on the server - the source
 * of truth in multiplayer - rather than what one player's screen happens to show.
 *
 * <p>Dispatches through {@link ClientMainThreadDispatch} exactly like the client reader does -
 * that helper is Minecraft-independent despite its name (it only needs something that can run a
 * {@link Runnable}), so it works equally well backed by a {@link MainThreadGateway} here. This
 * keeps both {@link BlockSnapshotReader} implementations safe to call from any thread, matching
 * the interface's real (if previously undocumented) contract - a caller that resolves this reader
 * via the interface type must not need to know which implementation enforces that safely.
 *
 * <p>Takes a {@code Supplier<ServerLevel>} rather than a single level reference so a dimension
 * reload or server restart (which replaces the {@link ServerLevel} instance) can't leave this
 * holding a dead level: each call resolves the current one fresh, and a {@code null} result (level
 * not currently available) is reported the same way the client reader reports "no level loaded" -
 * as {@link Optional#empty()}, not a {@code NullPointerException}.
 *
 * <p>Not yet wired into any production entry point - the still-to-be-built Factory Analyzer /
 * construction-verification loop will be its first caller.
 */
public final class ServerBlockSnapshotReader implements BlockSnapshotReader {
    private static final long MAIN_THREAD_TIMEOUT_MS = 2000L;

    private final MainThreadGateway gateway;
    private final Supplier<ServerLevel> levelSupplier;

    public ServerBlockSnapshotReader(MainThreadGateway gateway, Supplier<ServerLevel> levelSupplier) {
        this.gateway = gateway;
        this.levelSupplier = levelSupplier;
    }

    @Override
    public Optional<String> read(int x1, int y1, int z1, int x2, int y2, int z2) {
        // A caller already on the main thread (e.g. a future Factory Analyzer running from
        // serverTick) must run inline rather than queue behind itself: queuing would deadlock the
        // wait, or - since runOnMainThread queues rather than blocking the caller - silently stall
        // for the full timeout and report "world state unavailable" even though the world is fine
        // (review finding). Only a caller on some OTHER thread needs the queue+timeout dispatch.
        if (gateway.isOnMainThread()) {
            return describe(x1, y1, z1, x2, y2, z2);
        }
        try {
            return ClientMainThreadDispatch.runAndWait(
                    gateway::runOnMainThread, () -> describe(x1, y1, z1, x2, y2, z2), MAIN_THREAD_TIMEOUT_MS);
        } catch (TimeoutException mainThreadUnresponsive) {
            return Optional.empty();
        }
    }

    private Optional<String> describe(int x1, int y1, int z1, int x2, int y2, int z2) {
        ServerLevel level = levelSupplier.get();
        if (level == null) {
            return Optional.empty();
        }
        return BlockRangeDescription.describe(level, x1, y1, z1, x2, y2, z2);
    }
}
