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

    /**
     * The most connections an expanded plan may hold: the plan's own plus the internal connections of every module
     * instance's template. This is a ruling, not a derivation. A connection places no cell, and neither the design (a
     * port may take any number of connections: 05 section 2 gives no arity rule) nor the code (the patcher checks that a
     * connection's ports exist, not how many connections a port already has) ties the connections to the parts, and no
     * registered part declares a port yet, so there is no largest port count to derive a number from. The number is the
     * cell limit, the size of the largest plan that can be built. If it is too low, a plan that really needs more
     * connections is refused with a clear issue and the constant is raised; if it is too high, the cost of an instances
     * times connections blow-up stays bounded (200,000 expanded connections held about 30 MB when measured).
     */
    public static final int MAX_EXPANDED_CONNECTIONS = MAX_CELLS;

    /** The Issue key and the Issue data name of a refusal because the expanded plan would hold too many connections. */
    public static final String KEY_CONNECTIONS = "connections";

    private BuildLimits() {
    }
}
