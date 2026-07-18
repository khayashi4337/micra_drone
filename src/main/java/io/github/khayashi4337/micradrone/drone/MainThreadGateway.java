package io.github.khayashi4337.micradrone.drone;

/**
 * Abstracts "run this on Minecraft's main thread" so the pacing/threading
 * protocol can be unit-tested without a real MinecraftServer.
 */
public interface MainThreadGateway {
    /** Schedule work to run on the main (server) thread. Safe to call from any thread. */
    void runOnMainThread(Runnable task);

    /** Current server tick count. */
    long currentTick();
}
