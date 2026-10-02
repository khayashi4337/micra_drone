package io.github.khayashi4337.micradrone.construction;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.github.khayashi4337.micradrone.construction.core.JobKind;
import io.github.khayashi4337.micradrone.construction.core.JobService;
import io.github.khayashi4337.micradrone.construction.core.WriteAheadLog;

/**
 * The development barrier of the forced-kill scenarios (Task 25, devkit only): armed with a barrier point, a job
 * kind and an occurrence number, it blocks the server thread the nth time a run of that kind crosses the point, so
 * the harness can kill the process exactly there. It is installed only when {@code -Dmicradrone.devBarriers=true}
 * and {@link ConstructionRuntime#installBarrier} hands it in; the production log never sees it. The point names are
 * {@link WriteAheadLog.Barrier}'s; {@code mid-frame} fires inside the file sink itself.
 */
public final class DevBarrier implements WriteAheadLog.Barrier {
    private final Object gate = new Object();
    private JobService service;
    private WriteAheadLog wal;
    private volatile boolean armed;
    private volatile boolean hit;
    private volatile String point;
    private volatile JobKind kind;
    private volatile int nth;
    private volatile int seen;
    private volatile long hitRun;

    /**
     * The service the running job's kind is read from and the log the hit's run is taken from; called once by
     * {@link ConstructionRuntime#installBarrier}. {@code Barrier#at} carries only the point name, so the kind of the
     * job inside its executor run comes from {@link JobService#placingJob()}.
     */
    void bind(JobService service, WriteAheadLog wal) {
        this.service = service;
        this.wal = wal;
    }

    /** Arms the barrier anew: the nth crossing of {@code point} inside a run of {@code kind}'s jobs hits it. */
    public void arm(String point, JobKind kind, int nth) {
        this.point = point;
        this.kind = kind;
        this.nth = nth;
        this.seen = 0;
        this.hit = false;
        this.hitRun = 0;
        this.armed = true;
    }

    public boolean armed() {
        return armed;
    }

    public boolean hit() {
        return hit;
    }

    public String point() {
        return point;
    }

    /** The WAL run whose entries the kill stopped inside; 0 before a hit. */
    public long run() {
        return hitRun;
    }

    @Override
    public void at(String point) {
        if (!armed || hit || service == null || wal == null || !point.equals(this.point)) {
            return;
        }
        if (service.placingJob().map(j -> j.kind() != kind).orElse(true)) {
            return;
        }
        if (++seen != nth) {
            return;
        }
        hit = true;
        hitRun = wal.lastRun();
        MicraDrone.LOGGER.warn("dev barrier hit at {} in run {} (job kind {}); the server thread waits for the kill",
                point, hitRun, kind);
        synchronized (gate) {
            while (hit) {
                try {
                    gate.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
