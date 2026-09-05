package io.github.khayashi4337.micradrone.lang;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * Real cross-thread behavior of {@link MicraSemaphore} - the producer/consumer pattern
 * create_task/attach_isr will rely on (one thread posts, another blocks in wait() until it does).
 */
class MicraSemaphoreTest {

    @Test
    void postBeforeWaitLetsWaitProceedImmediately() {
        MicraSemaphore sem = new MicraSemaphore(0);
        sem.post();
        assertTimeout(Duration.ofSeconds(2), sem::await); // must not block - a permit is already available
    }

    @Test
    void waitBlocksUntilAnotherThreadPosts() throws InterruptedException {
        MicraSemaphore sem = new MicraSemaphore(0);
        Thread waiter = new Thread(sem::await);
        waiter.setDaemon(true);
        waiter.start();

        Thread.sleep(50);
        assertTrue(waiter.isAlive(), "waiter should still be blocked before post()");

        sem.post();
        waiter.join(2000);
        assertFalse(waiter.isAlive(), "waiter should have woken up after post()");
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
        waiter.join(2000);

        assertFalse(waiter.isAlive());
        assertInstanceOf(ScriptStoppedException.class, caught.get());
    }

    @Test
    void multiplePostsAllowThatManyWaitsToProceed() {
        MicraSemaphore sem = new MicraSemaphore(0);
        sem.post();
        sem.post();
        assertTimeout(Duration.ofSeconds(2), () -> {
            sem.await();
            sem.await(); // both permits consumed without blocking
        });
    }

    @Test
    void initialPermitsAreAvailableImmediately() {
        MicraSemaphore sem = new MicraSemaphore(2);
        assertTimeout(Duration.ofSeconds(2), () -> {
            sem.await();
            sem.await(); // both starting permits consumed without blocking
        });
    }
}
