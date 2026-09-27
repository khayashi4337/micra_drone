package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.Facing;

/** The four-direction parameters (north/east/south/west) as {@link Facing}, and the "right hand" of a direction. */
final class Dirs {
    /** One quarter turn clockwise, seen from above. */
    private static final int CLOCKWISE_QUARTER_TURN = 1;

    private Dirs() {
    }

    static Facing of(String name) {
        return Facing.parse(name);
    }

    /** The next direction clockwise seen from above: the right-hand side of a walker heading in {@code heading}. */
    static Facing right(Facing heading) {
        return heading.rotate(CLOCKWISE_QUARTER_TURN);
    }

    /** The u and w offsets of one cell of a run, as returned by {@link #offset}. */
    record Offset(int u, int w) {
    }

    /**
     * The (u, w) offset of the cell that lies {@code ahead} cells along {@code heading} and {@code toTheRight} cells to
     * the right-hand side of it: how a run (a road, a staircase, a ramp, a walkway) lays out its length and its width.
     */
    static Offset offset(Facing heading, int ahead, int toTheRight) {
        Facing right = right(heading);
        return new Offset(ahead * heading.du() + toTheRight * right.du(),
                ahead * heading.dw() + toTheRight * right.dw());
    }
}
