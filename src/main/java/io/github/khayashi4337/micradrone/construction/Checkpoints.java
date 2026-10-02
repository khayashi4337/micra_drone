package io.github.khayashi4337.micradrone.construction;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.github.khayashi4337.micradrone.construction.core.JobService;
import io.github.khayashi4337.micradrone.construction.core.WriteAheadLog;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Durable points on a live server (Task 25, 04 F-2): a job that waits for one gets a flushed save at most once a
 * minute, and while runs stay open past the durable point one is made on the autosave's own five-minute cadence. A
 * point is marked only after {@code saveAllChunks(..., flush=true)} returned with the job files written inside it
 * (the {@code LevelEvent.Save} handler writes them) and no recording failure on the way. A dimension under
 * {@code /save-off} cannot take a durable point: the jobs keep waiting until {@code /save-on}.
 */
final class Checkpoints {
    /** At most one durable point a minute (20 ticks/s * 60): a waiting job's flush is not allowed to spam. */
    static final long CHECKPOINT_MIN_SPACING_TICKS = 1200;
    /** Runs open past the durable point get one every five minutes, on the autosave's own cadence. */
    static final long CHECKPOINT_INTERVAL_TICKS = 6000;

    private final MinecraftServer server;
    private final JobService service;
    private final WriteAheadLog wal;
    /** {@code ConstructionRuntime#halted}: a save that failed while writing job files never earns a durable point. */
    private final BooleanSupplier halted;
    /** How a failed point stops construction: {@code ConstructionRuntime#haltRecording}. */
    private final BiConsumer<String, Throwable> halt;
    /** The tick of the last durable point actually marked. */
    private long lastPoint;
    /** The tick of the last attempt, refused or not, so a {@code /save-off} server is not warned on every tick. */
    private long lastTry;

    Checkpoints(MinecraftServer server, JobService service, WriteAheadLog wal, BooleanSupplier halted,
                BiConsumer<String, Throwable> halt) {
        this.server = server;
        this.service = service;
        this.wal = wal;
        this.halted = halted;
        this.halt = halt;
        // a first checkpoint is due right away when the log says so, not a full interval after start-up
        lastPoint = Long.MIN_VALUE / 2;
        lastTry = Long.MIN_VALUE / 2;
    }

    /** End of the tick: the spaced checkpoint, when one is due. All on the main thread. */
    void tickEnd(long tick) {
        if (halted.getAsBoolean()) {
            return;
        }
        boolean wanted = service.checkpointWanted();
        boolean runsOpen = wal.lastRun() > wal.durableUpTo();
        if (!wanted && !runsOpen) {
            return;
        }
        if (tick - lastTry < CHECKPOINT_MIN_SPACING_TICKS) {
            return;
        }
        if (!wanted && tick - lastPoint < CHECKPOINT_INTERVAL_TICKS) {
            return;
        }
        make(tick);
    }

    /**
     * The durable point itself. Everything the log covers must be on the disk first: the flushed server save writes
     * the world and the chests, the job files were written inside its save events (or just before, at the state
     * change) with their own forced writes, and the point is marked only when nothing on that path failed.
     */
    void make(long tick) {
        lastTry = tick;
        if (savingOff()) {
            MicraDrone.LOGGER.warn("construction checkpoint refused: a dimension is saved off; the jobs keep waiting"
                    + " until /save-on");
            return;
        }
        try {
            server.saveAllChunks(true, true, false);
        } catch (RuntimeException e) {
            halt.accept("a flushed save failed inside a checkpoint", e);
            return;
        }
        if (savingOff() || halted.getAsBoolean()) {
            // a skipped level or a job-file write that failed on the way out is not a durable point
            MicraDrone.LOGGER.warn("construction checkpoint did not mark a durable point: the save did not cover"
                    + " every job file");
            return;
        }
        try {
            service.markDurable();
            lastPoint = tick;
        } catch (RuntimeException e) {
            halt.accept("the durable point could not be written to the log", e);
        }
    }

    /** Any dimension with saving turned off: its chunks - and the job files its save event would write - did not go. */
    private boolean savingOff() {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.noSave()) {
                return true;
            }
        }
        return false;
    }
}
