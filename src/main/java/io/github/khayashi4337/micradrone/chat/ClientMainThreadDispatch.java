package io.github.khayashi4337.micradrone.chat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Runs a task on the render thread and waits (with a timeout) for its result - what
 * BlockSnapshotToolServer's HTTP handler thread uses to read Minecraft world state safely
 * (Codex review finding: never touch ClientLevel from a non-render thread directly). Takes a
 * {@link MainThreadExecutor} rather than calling {@code Minecraft.getInstance()} itself so the
 * timeout/completion logic here stays unit-testable with a fake.
 */
public final class ClientMainThreadDispatch {
    private ClientMainThreadDispatch() {
    }

    /**
     * Submits {@code task} to {@code executor} and blocks the calling thread for up to
     * {@code timeoutMs} for its result.
     *
     * @throws TimeoutException if the render thread doesn't run the task in time (e.g. the game
     *                          is paused, or the client is under heavy load)
     */
    public static <T> T runAndWait(MainThreadExecutor executor, Supplier<T> task, long timeoutMs) throws TimeoutException {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(task.get());
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }
}
