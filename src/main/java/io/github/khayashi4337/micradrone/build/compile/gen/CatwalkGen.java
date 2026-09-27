package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;
import io.github.khayashi4337.micradrone.build.parts.PartParams;
import io.github.khayashi4337.micradrone.build.parts.Roles;

/** A flat walkway with an optional fence along each of its two outer columns; its width is to the right of its direction. */
final class CatwalkGen implements PartGenerator {
    private static final int FLOOR_ROW = 0;
    /** The fence stands on the floor. */
    private static final int RAIL_ROW = FLOOR_ROW + 1;
    private static final int FIRST_COLUMN = 0;

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        Facing dir = Dirs.of(p.s(PartParams.DIR));
        int length = p.i(PartParams.LENGTH);
        int width = p.i(PartParams.WIDTH);
        boolean rail = p.b(PartParams.RAIL);
        int lastColumn = width - 1;
        // Every block that will be placed is asked of the palette before the first one goes down (a refused material
        // leaves no cell behind), and only those that will be placed: the fence role is asked for by a railed catwalk only.
        BlockSpec floor = BlockForms.flat(ctx.palette().full(p.s(PartParams.MATERIAL), node));
        BlockSpec fence = rail ? ctx.plainBlock(Roles.FENCE, node) : null;
        for (int i = 0; i < length; i++) {
            for (int j = 0; j < width; j++) {
                Dirs.Offset o = Dirs.offset(dir, i, j);
                ctx.emit(node, o.u(), FLOOR_ROW, o.w(), floor);
                // a one-wide walkway has one column that is both outer columns, and gets one fence
                if (rail && (j == FIRST_COLUMN || j == lastColumn)) {
                    ctx.emit(node, o.u(), RAIL_ROW, o.w(), fence);
                }
            }
        }
    }
}
