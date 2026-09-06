package io.github.khayashi4337.micradrone.chat;

/**
 * Abstracts "run this on the render thread" so ClientMainThreadDispatch's timeout/future logic
 * can be unit-tested without a real Minecraft client - the client-side analogue of this project's
 * existing (server-side) MainThreadGateway interface.
 */
public interface MainThreadExecutor {
    void execute(Runnable task);
}
