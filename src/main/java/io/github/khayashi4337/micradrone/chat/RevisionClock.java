package io.github.khayashi4337.micradrone.chat;

/**
 * Decides which server echo of the breakpoint set is newer than what the client already has - the
 * whole of the "revision" scheme described on {@code SetBreakpointsPayload#revision}, kept here as
 * plain integer arithmetic so it can be unit-tested (the screen that uses it is Minecraft-side and
 * cannot be).
 *
 * <p>The counter starts at 0 on every {@code IdeScreen}, since the screen is new each time it is
 * opened, while the SERVER's counter keeps whatever a previous session left it at. {@link #accept}
 * therefore seeds this clock from the server's number every time an echo is taken, so the next
 * {@link #nextSend} outranks anything either side has seen - without that, reopening a controller
 * whose server-side revision had reached N would leave the session's first few sends numbered at or
 * below N, and every stale echo would then outrank them and undo the edit in progress.
 */
public final class RevisionClock {
    /** The highest revision this clock has issued or been shown - see {@link #accept}'s seeding. */
    private int highestSeen = 0;

    /**
     * Takes an echo carrying {@code serverRevision} if it is at least as new as this client's own
     * last send, and reports whether it was taken. Taking one seeds the clock (see the class doc);
     * refusing leaves it alone, since it is already above {@code serverRevision} by definition.
     */
    public boolean accept(int serverRevision) {
        if (serverRevision < highestSeen) {
            return false;
        }
        highestSeen = serverRevision;
        return true;
    }

    /** The revision to stamp on the next send - strictly above every revision seen so far. */
    public int nextSend() {
        return ++highestSeen;
    }
}
