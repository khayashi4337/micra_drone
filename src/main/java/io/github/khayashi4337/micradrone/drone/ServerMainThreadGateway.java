package io.github.khayashi4337.micradrone.drone;

import net.minecraft.server.MinecraftServer;

/** Production {@link MainThreadGateway} backed by the real MinecraftServer. */
public final class ServerMainThreadGateway implements MainThreadGateway {
    private final MinecraftServer server;

    public ServerMainThreadGateway(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public void runOnMainThread(Runnable task) {
        server.execute(task);
    }

    @Override
    public long currentTick() {
        return server.getTickCount();
    }
}
