package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;

/** A door in a wall: one or two leaves, or a hangar opening closed by a row of gates. */
final class DoorGen implements PartGenerator {
    private static final String P_WIDTH = "width";
    private static final String P_HEIGHT = "height";
    private static final String P_HINGE = "hinge";
    private static final String KIND_SINGLE = "single";
    private static final String KIND_DOUBLE = "double";
    private static final String KIND_HANGAR = "hangar";
    private static final String HINGE_RIGHT = "right";
    /** The palette role of a hangar door's gates. */
    private static final String ROLE_GATE = "gate";
    private static final int SINGLE_WIDTH = 1;
    private static final int DOUBLE_WIDTH = 2;
    /** A door leaf is two blocks high: a lower half and an upper half. */
    private static final int LEAF_HEIGHT = 2;
    private static final int LOWER_ROW = 0;
    private static final int UPPER_ROW = 1;
    /** A hangar door's gates fill the lowest rows of its opening; the rows above them stay empty. */
    private static final int GATE_ROWS = 2;
    private static final int FIRST_LEAF = 0;

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        String kind = p.s(OpeningSpot.P_KIND);
        boolean hangar = kind.equals(KIND_HANGAR);
        int width = switch (kind) {
            case KIND_SINGLE -> SINGLE_WIDTH;
            case KIND_DOUBLE -> DOUBLE_WIDTH;
            default -> p.i(P_WIDTH);
        };
        int height = hangar ? p.i(P_HEIGHT) : LEAF_HEIGHT;
        OpeningSpot spot = OpeningSpot.carved(ctx, node, width, height);
        // doors and gates face indoors; an INNER opening faces outdoors
        Facing facing = spot.outer() ? spot.wall().outward().opposite() : spot.wall().outward();
        if (hangar) {
            placeGates(ctx, node, spot, facing);
        } else {
            placeLeaves(ctx, node, p, kind, spot, facing);
        }
    }

    /** The block is asked of the palette before the first gate goes down, so a refused role leaves no gate behind. */
    private static void placeGates(GenContext ctx, PlanNode node, OpeningSpot spot, Facing facing) {
        String gate = ctx.palette().full(ROLE_GATE, node);
        for (int di = 0; di < spot.width(); di++) {
            for (int dr = 0; dr < Math.min(GATE_ROWS, spot.height()); dr++) {
                ctx.emitAbs(node, spot.at(di, dr), BlockForms.gate(gate, facing));
            }
        }
    }

    /** The block is asked of the palette before the first leaf goes down, so a refused material leaves no leaf behind. */
    private static void placeLeaves(GenContext ctx, PlanNode node, Params p, String kind, OpeningSpot spot, Facing facing) {
        String door = ctx.palette().full(p.s(OpeningSpot.P_MATERIAL), node);
        // The leaves of a double door are hinged on their outer edges. The walker who goes through heads the way the door
        // faces; when the wall grows toward the walker's right, the first leaf (the one at the wall's start) has its outer
        // edge on the walker's left, so it is hinged left and the second leaf right. When the wall grows toward the
        // walker's left instead, it is the other way round.
        boolean firstLeafHingeLeft = Dirs.right(facing) == spot.wall().along();
        for (int leaf = FIRST_LEAF; leaf < spot.width(); leaf++) {
            boolean hingeRight;
            if (kind.equals(KIND_DOUBLE)) {
                hingeRight = leaf == FIRST_LEAF ? !firstLeafHingeLeft : firstLeafHingeLeft;
            } else {
                hingeRight = p.s(P_HINGE).equals(HINGE_RIGHT);
            }
            ctx.emitAbs(node, spot.at(leaf, LOWER_ROW), BlockForms.door(door, facing, false, hingeRight));
            ctx.emitAbs(node, spot.at(leaf, UPPER_ROW), BlockForms.door(door, facing, true, hingeRight));
        }
    }
}
