package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;

/** A brick shaft, hollow when it is three blocks wide, with an optional cap of slabs that overhangs it. */
final class ChimneyGen implements PartGenerator {
    private static final String P_HEIGHT = "height";
    private static final String P_SIZE = "size";
    private static final String P_CAP = "cap";
    private static final String P_MATERIAL = "material";
    /** A chimney of this size is a ring around an open flue; a smaller one is solid. */
    private static final int FLUE_SIZE = 3;
    /** The middle cell of a {@link #FLUE_SIZE} row. */
    private static final int FLUE_INDEX = 1;
    /** The cap reaches this many blocks past the shaft on every side. */
    private static final int CAP_OVERHANG = 1;

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        int height = p.i(P_HEIGHT);
        int size = p.i(P_SIZE);
        BlockSpec shaft = ctx.plainBlock(p.s(P_MATERIAL), node);
        // The cap's block is asked of the palette before the first cell of the shaft is placed: a cap that cannot be made
        // (a material with no slab form) must not leave the shaft behind. A chimney without a cap asks for no slab.
        BlockSpec cap = p.b(P_CAP) ? BlockForms.slab(ctx.palette().slab(p.s(P_MATERIAL), node), false) : null;
        for (int dv = 0; dv < height; dv++) {
            for (int u = 0; u < size; u++) {
                for (int w = 0; w < size; w++) {
                    if (isFlue(size, u, w)) {
                        ctx.canvas().charge(); // the flue is considered and left empty
                    } else {
                        ctx.emit(node, u, dv, w, shaft);
                    }
                }
            }
        }
        if (cap != null) {
            for (int u = -CAP_OVERHANG; u < size + CAP_OVERHANG; u++) {
                for (int w = -CAP_OVERHANG; w < size + CAP_OVERHANG; w++) {
                    ctx.emit(node, u, height, w, cap);
                }
            }
        }
    }

    private static boolean isFlue(int size, int u, int w) {
        return size == FLUE_SIZE && u == FLUE_INDEX && w == FLUE_INDEX;
    }
}
