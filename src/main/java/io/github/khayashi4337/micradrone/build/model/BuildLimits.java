package io.github.khayashi4337.micradrone.build.model;

/** Size limits of a build that the plan side (the expander) and the compile side (the compiler) must agree on. */
public final class BuildLimits {
    /**
     * The most cells one compile places, and so the most parts an expanded plan may hold: a part that has blocks places
     * at least one cell, or lands on a cell that is taken (an error), so a plan with more parts than this cannot
     * produce a manifest. The one part without blocks of its own is the building (micra:structure), which only gives
     * its children a frame.
     */
    public static final int MAX_CELLS = 200_000;

    /** The Issue key and the Issue data name of a refusal because the build is too big (E-OUT-OF-BOUNDS). */
    public static final String KEY_CELLS = "cells";

    private BuildLimits() {
    }
}
