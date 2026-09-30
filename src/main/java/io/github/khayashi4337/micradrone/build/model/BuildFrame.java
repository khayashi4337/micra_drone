package io.github.khayashi4337.micradrone.build.model;

/**
 * Maps the local frame (u right, v up, w forward) to the world. With {@code facing == NORTH} the world offset
 * is (u, v, -w) (Minecraft's north is -z); the other facings turn the horizontal part clockwise by the facing's
 * quarter-turn count. Plans are written in local coordinates, so rotating a plan is only a change of facing.
 */
public record BuildFrame(IntPos origin, Facing facing) {
    public IntPos toWorld(LocalPos p) {
        int dx = p.u();
        int dz = -p.w();
        for (int i = 0; i < facing.quarterTurns(); i++) {
            int nextDx = -dz;
            int nextDz = dx;
            dx = nextDx;
            dz = nextDz;
        }
        return new IntPos(origin.x() + dx, origin.y() + p.v(), origin.z() + dz);
    }

    public LocalPos toLocal(IntPos p) {
        int dx = p.x() - origin.x();
        int dz = p.z() - origin.z();
        for (int i = 0; i < facing.quarterTurns(); i++) {
            int nextDx = dz;
            int nextDz = -dx;
            dx = nextDx;
            dz = nextDz;
        }
        return new LocalPos(dx, p.y() - origin.y(), -dz);
    }

    public Facing toWorldFacing(Facing local) {
        return local.rotate(facing.quarterTurns());
    }

    public Facing toLocalFacing(Facing world) {
        return world.rotate(-facing.quarterTurns());
    }
}
