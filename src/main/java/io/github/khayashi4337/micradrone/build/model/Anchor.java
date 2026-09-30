package io.github.khayashi4337.micradrone.build.model;

import java.util.Objects;

/**
 * Where a node sits. {@code Absolute} is relative to the parent's origin (or to the plan origin when there is
 * no parent); {@code OnSurface} is a position on a wall face (u along the wall's direction of growth, v up from
 * the wall's lowest row); {@code InSlot} needs the slot resolution that arrives with the building analysis.
 */
public sealed interface Anchor {
    record Absolute(LocalPos pos, Rot rot) implements Anchor {
        public Absolute {
            Objects.requireNonNull(pos, "pos");
            rot = Objects.requireNonNullElse(rot, Rot.NONE);
        }
    }

    record OnSurface(String nodeId, Side side, int u, int v) implements Anchor {
        public OnSurface {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(side, "side");
        }
    }

    record InSlot(String slotId, Rot rot) implements Anchor {
        public InSlot {
            Objects.requireNonNull(slotId, "slotId");
            rot = Objects.requireNonNullElse(rot, Rot.NONE);
        }
    }
}
