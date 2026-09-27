package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;
import io.github.khayashi4337.micradrone.build.parts.PartParams;

/**
 * A ramp that rises half a block per cell: a bottom slab, then a top slab in the same block, then a bottom slab one
 * block up, and so on. Its width is to the right-hand side of its direction.
 */
final class RampGen implements PartGenerator {
    /** A bottom slab and the top slab after it make one block of height. */
    private static final int CELLS_PER_BLOCK = 2;

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        Facing dir = Dirs.of(p.s(PartParams.DIR));
        int length = p.i(PartParams.LENGTH);
        int width = p.i(PartParams.WIDTH);
        String slab = ctx.palette().slab(p.s(PartParams.MATERIAL), node);
        for (int i = 0; i < length; i++) {
            boolean upperHalf = i % CELLS_PER_BLOCK != 0;
            BlockSpec block = BlockForms.slab(slab, upperHalf);
            int dv = i / CELLS_PER_BLOCK;
            for (int j = 0; j < width; j++) {
                Dirs.Offset o = Dirs.offset(dir, i, j);
                ctx.emit(node, o.u(), dv, o.w(), block);
            }
        }
    }
}
