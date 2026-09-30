package io.github.khayashi4337.micradrone.chat;

/**
 * Abstracts "run this on the main thread" so {@link ClientMainThreadDispatch}'s timeout/future
 * logic can be unit-tested without a real Minecraft client or server. Originally written as the
 * client-side analogue of this project's {@code drone.MainThreadGateway} interface, but the two
 * interfaces have the same single-method shape (both take a {@code Runnable}), so a
 * {@code MainThreadGateway::runOnMainThread} method reference satisfies this interface directly -
 * see {@code drone.ServerBlockSnapshotReader} for a server-side caller doing exactly that.
 */
public interface MainThreadExecutor {
    void execute(Runnable task);
}
