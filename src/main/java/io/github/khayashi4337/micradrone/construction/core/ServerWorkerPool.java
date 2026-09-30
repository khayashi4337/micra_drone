package io.github.khayashi4337.micradrone.construction.core;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The bounded, cancellable pool for the heavy pure work (04 F-1): expand, compile, terraform, safety check. One job per
 * player at a time: the player's slot is held until the work has really left its thread, even after a cancel, so a
 * cancelled compile can never run side by side with the next one. Cancelling sets a token the work may poll and
 * interrupts its thread; a job still in the queue is removed and reported cancelled at once. The queue is bounded;
 * results are handed back on the main thread, never touching the world here.
 */
public final class ServerWorkerPool implements AutoCloseable {
    public static final int DEFAULT_THREADS = 2;
    public static final int DEFAULT_MAX_QUEUED = 8;
    private static final String THREAD_NAME = "MicraDrone-BuildWorker-";

    /** A cancellation the work can poll between its steps. */
    public static final class CancelToken {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        public boolean cancelled() {
            return cancelled.get();
        }
    }

    /** Work that may look at its token. */
    @FunctionalInterface
    public interface Work<T> {
        T run(CancelToken token) throws Exception;
    }

    private final class Job<T> implements Runnable {
        final UUID owner;
        final Work<T> work;
        final Consumer<WorkResult<T>> onMain;
        final CancelToken token = new CancelToken();
        volatile Thread runner;

        Job(UUID owner, Work<T> work, Consumer<WorkResult<T>> onMain) {
            this.owner = owner;
            this.work = work;
            this.onMain = onMain;
        }

        @Override
        public void run() {
            WorkResult<T> result;
            runner = Thread.currentThread();
            try {
                if (token.cancelled()) {
                    result = new WorkResult.Cancelled<>();
                } else {
                    T value = work.run(token);
                    result = token.cancelled() ? new WorkResult.Cancelled<>() : new WorkResult.Done<>(value);
                }
            } catch (Throwable t) {
                result = token.cancelled() ? new WorkResult.Cancelled<>() : new WorkResult.Failed<>(t);
            } finally {
                runner = null;
                Thread.interrupted();
            }
            finish(this, result);
        }
    }

    private final ThreadPoolExecutor pool;
    private final Executor mainThread;
    private final Map<UUID, Job<?>> active = new ConcurrentHashMap<>();
    private final AtomicInteger threads = new AtomicInteger();

    public ServerWorkerPool(int threadCount, int maxQueued, Executor mainThread) {
        this.mainThread = mainThread;
        this.pool = new ThreadPoolExecutor(threadCount, threadCount, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxQueued), r -> {
                    Thread t = new Thread(r, THREAD_NAME + threads.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public <T> boolean submit(UUID owner, Callable<T> work, Consumer<WorkResult<T>> onMain) {
        return submit(owner, (Work<T>) token -> work.call(), onMain);
    }

    public synchronized <T> boolean submit(UUID owner, Work<T> work, Consumer<WorkResult<T>> onMain) {
        if (active.containsKey(owner)) {
            return false;
        }
        Job<T> job = new Job<>(owner, work, onMain);
        active.put(owner, job);
        try {
            pool.execute(job);
        } catch (RejectedExecutionException full) {
            active.remove(owner, job);
            return false;
        }
        return true;
    }

    /** True while the player's work has not yet left its thread (a cancelled one included). */
    public boolean busy(UUID owner) {
        return active.containsKey(owner);
    }

    public synchronized boolean cancel(UUID owner) {
        Job<?> job = active.get(owner);
        if (job == null || job.token.cancelled.getAndSet(true)) {
            return false;
        }
        if (pool.remove(job)) {
            finish(job, new WorkResult.Cancelled<>());
            return true;
        }
        Thread t = job.runner;
        if (t != null) {
            t.interrupt();
        }
        return true;
    }

    private <T> void finish(Job<T> job, WorkResult<?> result) {
        @SuppressWarnings("unchecked")
        WorkResult<T> r = (WorkResult<T>) result;
        mainThread.execute(() -> {
            active.remove(job.owner, job);
            job.onMain.accept(r);
        });
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
