package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;

/**
 * A ramp that rises half a block per cell: a bottom slab, then a top slab in the same block, then a bottom slab one
 * block up, and so on. Its width is to the right-hand side of its direction.
 */
final class RampGen implements PartGenerator {
    private static final String P_LENGTH = "length";
    private static final String P_WIDTH = "width";
    private static final String P_DIR = "dir";
    private static final String P_MATERIAL = "material";
    /** A bottom slab and the top slab after it make one block of height. */
    private static final int CELLS_PER_BLOCK = 2;

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        Facing dir = Dirs.of(p.s(P_DIR));
        int length = p.i(P_LENGTH);
        int width = p.i(P_WIDTH);
        String slab = ctx.palette().slab(p.s(P_MATERIAL), node);
        for (int i = 0; i < length; i++) {
            boolean upperHalf = i % CELLS_PER_BLOCK != 0;
            BlockSpec block = BlockForms.slab(slab, upperHalf);
            int dv = i / CELLS_PER_BLOCK;
            for (int j = 0; j < width; j++) {
                ctx.emit(node, Dirs.du(dir, i, j), dv, Dirs.dw(dir, i, j), block);
            }
        }
    }
}
