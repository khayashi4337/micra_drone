package io.github.khayashi4337.micradrone.lang;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * Real cross-thread behavior of {@link MicraSemaphore} - the producer/consumer pattern
 * create_task/attach_isr will rely on (one thread posts, another blocks in wait() until it does).
 *
 * <p>Every "must not block" case runs the call on a background thread and bounds the wait with
 * {@code join(timeoutMillis)} rather than JUnit's {@code assertTimeout} - that assertion runs the
 * runnable on the calling thread and only inspects elapsed time *after* it returns, so a real
 * infinite-block regression would hang the test (and the whole run) forever instead of failing.
 */
class MicraSemaphoreTest {

    private static final long WAIT_MILLIS = 2000;

    /** Runs {@code action} on its own thread, capturing any exception, and asserts it finished (not blocked) within WAIT_MILLIS. */
    private static void assertCompletesPromptly(String what, Runnable action) throws InterruptedException {
        AtomicReference<Throwable> caught = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                caught.set(t);
            }
        });
        worker.setDaemon(true);
        worker.start();
        worker.join(WAIT_MILLIS);
        if (worker.isAlive()) {
            fail(what + " did not complete within " + WAIT_MILLIS + "ms - looks blocked");
        }
        assertNull(caught.get(), () -> what + " threw an unexpected exception: " + caught.get());
    }

    @Test
    void aFreshSemaphoreStartsWithZeroPermitsSoWaitBlocks() throws InterruptedException {
        MicraSemaphore sem = new MicraSemaphore(0);
        Thread waiter = new Thread(sem::await);
        waiter.setDaemon(true);
        waiter.start();

        Thread.sleep(50);
        assertTrue(waiter.isAlive(), "wait() on a fresh semaphore(0) should block - it must not start with a permit already available");

        sem.post();
        waiter.join(WAIT_MILLIS);
        assertFalse(waiter.isAlive(), "waiter should have woken up after post()");
    }

    @Test
    void postBeforeWaitLetsWaitProceedImmediately() throws InterruptedException {
        MicraSemaphore sem = new MicraSemaphore(0);
        sem.post();
        assertCompletesPromptly("wait() after a post()", sem::await);
    }

    @Test
    void waitBlocksUntilAnotherThreadPosts() throws InterruptedException {
        MicraSemaphore sem = new MicraSemaphore(0);
        AtomicReference<Throwable> caught = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                sem.await();
            } catch (Throwable t) {
                caught.set(t);
            }
        });
        waiter.setDaemon(true);
        waiter.start();

        Thread.sleep(50);
        assertTrue(waiter.isAlive(), "waiter should still be blocked before post()");

        sem.post();
        waiter.join(WAIT_MILLIS);
        assertFalse(waiter.isAlive(), "waiter should have woken up after post()");
        assertNull(caught.get(), () -> "waiter threw an unexpected exception: " + caught.get());
    }

    @Test
    void interruptingAWaitingThreadThrowsScriptStoppedException() throws InterruptedException {
        MicraSemaphore sem = new MicraSemaphore(0);
        AtomicReference<Throwable> caught = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                sem.await();
            } catch (Throwable t) {
                caught.set(t);
            }
        });
        waiter.setDaemon(true);
        waiter.start();

        Thread.sleep(50);
        waiter.interrupt();
        waiter.join(WAIT_MILLIS);

        assertFalse(waiter.isAlive());
        assertInstanceOf(ScriptStoppedException.class, caught.get());
    }

    @Test
    void multiplePostsAllowThatManyWaitsToProceed() throws InterruptedException {
        MicraSemaphore sem = new MicraSemaphore(0);
        sem.post();
        sem.post();
        assertCompletesPromptly("two await() calls after two post()s", () -> {
            sem.await();
            sem.await();
        });
    }

    @Test
    void initialPermitsAreAvailableImmediately() throws InterruptedException {
        MicraSemaphore sem = new MicraSemaphore(2);
        assertCompletesPromptly("two await() calls on a semaphore(2)", () -> {
            sem.await();
            sem.await();
        });
    }
}
