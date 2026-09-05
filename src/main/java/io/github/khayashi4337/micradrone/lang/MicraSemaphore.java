package io.github.khayashi4337.micradrone.lang;

import java.util.concurrent.Semaphore;

/**
 * A script-visible counting semaphore, backed by {@link Semaphore}. The canonical way for one
 * task/ISR handler to hand work off to another without a shared list/dict/set racing: post()
 * never blocks, wait() blocks the calling thread until a permit is available. Not a builtin
 * function on its own - {@code semaphore()} creates one, and {@code .post()}/{@code .wait()} are
 * dispatched the same way list/dict/set methods are (see {@link Interpreter#evalMethodCall}).
 */
public final class MicraSemaphore {
    private final Semaphore delegate;

    public MicraSemaphore(int initialPermits) {
        this.delegate = new Semaphore(initialPermits);
    }

    /** Releases a permit; never blocks. */
    public void post() {
        delegate.release();
    }

    /** Blocks the calling thread until a permit is available. Stop (interrupt) converts to ScriptStoppedException. */
    public void await() {
        try {
            delegate.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScriptStoppedException();
        }
    }
}
