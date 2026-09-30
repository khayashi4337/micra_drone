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

    /**
     * True if the calling thread already is the main thread. Lets a caller that needs a result
     * back (unlike {@link #runOnMainThread}, which is fire-and-forget) run inline when it's already
     * safe to, instead of queuing behind whatever the main thread is currently doing and waiting on
     * a timeout for its own turn - see {@code ServerBlockSnapshotReader} for why that distinction
     * matters (a review found calling it from the main thread during another task could otherwise
     * make it wrongly report "world state unavailable" after stalling out).
     */
    boolean isOnMainThread();
}
