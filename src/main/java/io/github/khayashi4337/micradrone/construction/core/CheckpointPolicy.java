package io.github.khayashi4337.micradrone.construction.core;

/**
 * When a durable point may be tried (design {@code 04} F-2). A job waiting for one to finish gets a
 * new try five seconds after the last try; runs that are merely still open past the durable point
 * keep the old throttle: one try a minute, and one point per five minutes on the autosave's own
 * cadence. Pure decision - the {@code construction} adapter ({@code Checkpoints}) supplies the tick
 * and performs the save.
 */
public final class CheckpointPolicy {
    /** At most one try a minute (20 ticks/s * 60): a flushed save must not spam the disk. */
    public static final long MIN_SPACING_TICKS = 1200;
    /** A job waiting for a durable point gets a retry five seconds (20 ticks/s * 5) after the last try. */
    public static final long WAITING_SPACING_TICKS = 100;
    /** Runs open past the durable point get one every five minutes, on the autosave's own cadence. */
    public static final long INTERVAL_TICKS = 6000;

    private CheckpointPolicy() {
    }

    /**
     * Whether a durable point is due this tick. {@code wanted}: a job sits {@code awaitingDurable}
     * (it may only end inside a durable point). {@code runsOpen}: the log holds runs past the last
     * durable point ({@code wal.lastRun() > wal.durableUpTo()}). {@code lastTry} is the tick of the
     * last attempt, refused or not; {@code lastPoint} the tick of the last point actually marked.
     * A waiting job's spacing is measured from the last try alone - the five-minute interval never
     * holds it back.
     */
    public static boolean due(boolean wanted, boolean runsOpen, long tick, long lastTry, long lastPoint) {
        if (!wanted && !runsOpen) {
            return false;
        }
        if (wanted) {
            return tick - lastTry >= WAITING_SPACING_TICKS;
        }
        return tick - lastTry >= MIN_SPACING_TICKS && tick - lastPoint >= INTERVAL_TICKS;
    }
}
