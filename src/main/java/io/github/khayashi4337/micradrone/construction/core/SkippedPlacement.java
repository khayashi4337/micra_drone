package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.Objects;

/**
 * A program position the job deliberately did not place, with why (04 F-3/F-5). Together with the journal this makes
 * every program position accounted for: either a JournalRecord exists, or a SkippedPlacement does.
 */
public record SkippedPlacement(int index, IntPos pos, String reason) {
    /** The position was refused (a permission denial, an unsafe replacement). */
    public static final String DENIED = "denied";
    /** The world at the position changed while the job was paused or running (F-3). */
    public static final String SITE_CHANGED = "site-changed";
    /** The placement itself was invalid for the world (the port refused it). */
    public static final String INVALID = "invalid";
    /** A removal conflict at the position blocks the placement (MODIFY). */
    public static final String CONFLICT = "conflict";
    /** A repair round found the project's block already correct: nothing is placed or charged. */
    public static final String ALREADY_THERE = "already-there";

    public SkippedPlacement {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(reason, "reason");
    }
}
