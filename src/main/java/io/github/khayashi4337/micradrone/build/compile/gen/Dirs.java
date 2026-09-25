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
}
