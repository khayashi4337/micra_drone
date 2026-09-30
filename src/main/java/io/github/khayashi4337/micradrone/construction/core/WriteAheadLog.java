package io.github.khayashi4337.micradrone.construction.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Appends log entries and makes them durable in one step per {@link #flush()} (the adapter's sink appends to segment
 * files under {@code wal/} and forces them to disk). Runs are numbered across all jobs. {@link #markDurable(long)} is
 * called only after a flushed save of the whole server; everything up to it is then dropped from the log, so the log
 * holds at most the runs since the last durable point.
 */
public final class WriteAheadLog {
    /** Where flushed entries go; {@code write} returns only when they are durable. */
    public interface Sink {
        void write(List<WalEntry> entries);

        /** Everything durable so far (read only when a job is recovered). */
        List<WalEntry> readAll();

        /** Drops every entry of the runs up to {@code run}, keeping the newest durable point (it keeps the numbering). */
        void dropUpTo(long run);
    }

    /**
     * A named place in the protocol where a test (or the devkit's crash barrier) may stop the server. The production
     * barrier does nothing.
     */
    public interface Barrier {
        String AFTER_INTENTS = "after-intents";
        String AFTER_WRITES = "after-writes";
        String AFTER_RUN_END = "after-run-end";
        String AFTER_PLAYER_SAVE = "after-player-save";
        /** Half of a log frame is on the disk (FileWalSink). */
        String MID_FRAME = "mid-frame";
        Barrier NONE = point -> {
        };

        void at(String point);
    }

    /** An in-memory sink for the pure tests; {@code durable} is what a crash would leave behind. */
    public static final class MemorySink implements Sink {
        public final List<WalEntry> durable = new ArrayList<>();
        public Runnable hook = () -> {
        };

        @Override
        public void write(List<WalEntry> entries) {
            hook.run();
            durable.addAll(entries);
            hook.run();
        }

        @Override
        public List<WalEntry> readAll() {
            return List.copyOf(durable);
        }

        @Override
        public void dropUpTo(long run) {
            WalEntry.DurablePoint newest = null;
            for (WalEntry e : durable) {
                if (e instanceof WalEntry.DurablePoint d && (newest == null || d.upToRun() > newest.upToRun())) {
                    newest = d;
                }
            }
            durable.removeIf(e -> WalEntry.runOf(e) <= run);
            if (newest != null && newest.upToRun() <= run) {
                durable.add(0, newest);
            }
        }
    }

    private final Sink sink;
    private final List<WalEntry> unflushed = new ArrayList<>();
    private long lastRun;
    private long durableUpTo;
    private Barrier barrier = Barrier.NONE;

    public WriteAheadLog(Sink sink, long lastRun) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.lastRun = lastRun;
    }

    public static WriteAheadLog inMemory() {
        return new WriteAheadLog(new MemorySink(), 0L);
    }

    /** Opens a log over what its sink holds; run numbers go on from the newest one there. */
    public static WriteAheadLog open(Sink sink) {
        List<WalEntry> all = sink.readAll();
        WriteAheadLog wal = new WriteAheadLog(sink, lastRunIn(all));
        wal.durableUpTo = durablePointIn(all);
        return wal;
    }

    /** The newest run number the entries name (0 for none); the durable point keeps it after a drop. */
    public static long lastRunIn(List<WalEntry> log) {
        long last = 0L;
        for (WalEntry e : log) {
            last = Math.max(last, WalEntry.runOf(e));
        }
        return last;
    }

    /** The newest durable point in the entries (0 for none: nothing is known to be on the disk). */
    public static long durablePointIn(List<WalEntry> log) {
        long d = 0L;
        for (WalEntry e : log) {
            if (e instanceof WalEntry.DurablePoint p) {
                d = Math.max(d, p.upToRun());
            }
        }
        return d;
    }

    public long newRun() {
        return ++lastRun;
    }

    public long lastRun() {
        return lastRun;
    }

    public long durableUpTo() {
        return durableUpTo;
    }

    public void append(WalEntry e) {
        unflushed.add(Objects.requireNonNull(e, "entry"));
    }

    public void flush() {
        if (unflushed.isEmpty()) {
            return;
        }
        List<WalEntry> batch = List.copyOf(unflushed);
        unflushed.clear();
        sink.write(batch);
    }

    /**
     * Called by the runtime only after a flushed save of the whole server returned: every run up to {@code upToRun} is
     * on the disk (world, chests, players, job files), so the log keeps only what came after.
     */
    public void markDurable(long upToRun) {
        append(new WalEntry.DurablePoint(upToRun));
        flush();
        durableUpTo = Math.max(durableUpTo, upToRun);
        sink.dropUpTo(upToRun);
    }

    public List<WalEntry> durable() {
        return sink.readAll();
    }

    public void setBarrier(Barrier b) {
        barrier = Objects.requireNonNull(b, "barrier");
    }

    public void at(String point) {
        barrier.at(point);
    }

    public Sink sink() {
        return sink;
    }
}
