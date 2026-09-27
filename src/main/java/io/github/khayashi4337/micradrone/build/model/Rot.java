package io.github.khayashi4337.micradrone.build.model;

/**
 * A rotation and optional mirror in the local frame. The mirror (u to -u, so EAST and WEST swap) is applied
 * first, then {@code quarterTurns} clockwise turns seen from above; one turn maps (u,w) to (w,-u).
 */
public record Rot(int quarterTurns, boolean mirror) {
    public static final int QUARTER_TURNS_PER_CIRCLE = 4;
    public static final int HALF_TURN = 2;

    public static final Rot NONE = new Rot(0, false);

    public Rot {
        quarterTurns = Math.floorMod(quarterTurns, QUARTER_TURNS_PER_CIRCLE);
    }

    public LocalPos apply(LocalPos p) {
        int u = mirror ? -p.u() : p.u();
        int w = p.w();
        for (int i = 0; i < quarterTurns; i++) {
            int nextU = w;
            int nextW = -u;
            u = nextU;
            w = nextW;
        }
        return new LocalPos(u, p.v(), w);
    }

    public Facing apply(Facing facing) {
        Facing mirrored = facing;
        if (mirror) {
            mirrored = facing == Facing.EAST ? Facing.WEST : facing == Facing.WEST ? Facing.EAST : facing;
        }
        return mirrored.rotate(quarterTurns);
    }

    /**
     * The transform "apply {@code inner} first, then {@code outer}". A mirror reverses the direction of any
     * rotation that follows it, hence the sign flip.
     */
    public static Rot compose(Rot outer, Rot inner) {
        int turns = outer.quarterTurns + (outer.mirror ? -inner.quarterTurns : inner.quarterTurns);
        return new Rot(turns, outer.mirror ^ inner.mirror);
    }
}
