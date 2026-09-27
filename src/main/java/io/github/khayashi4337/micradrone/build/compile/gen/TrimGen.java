package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.parts.Params;
import io.github.khayashi4337.micradrone.build.parts.PartParams;

/**
 * A decorative course along a wall face: a run of blocks or bottom slabs, along the wall (horizontal) or up it
 * (vertical), on the first cell outside the wall (OUTER) or the first cell inside (INNER). The course lies on the
 * wall it is attached to: a length that runs past the wall's end or top is refused.
 */
final class TrimGen implements PartGenerator {
    private static final String AXIS_HORIZONTAL = "horizontal";
    private static final String SHAPE_SLAB = "slab";

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        WallInfo wall = ctx.wallOfAnchor(node); // refuses anything but an OnSurface anchor on a wall, so the cast holds
        Anchor.OnSurface a = (Anchor.OnSurface) node.anchor();
        int layer = wall.faceLayer(a.side() == Side.OUTER);
        int length = p.i(PartParams.LENGTH);
        boolean horizontal = p.s(PartParams.AXIS).equals(AXIS_HORIZONTAL);
        // The course lies on the wall it is attached to. Checked before anything is looked up or placed, so a refusal
        // leaves no cell behind.
        if (horizontal) {
            ctx.requireAlongWall(node, wall, a.u(), length, "縁取り");
        } else {
            ctx.requireUpWall(node, wall, a.v(), length, "縁取り");
        }
        // Asked of the palette before the first cell goes down, and only the form that is placed: the slab form of
        // the material for a slab course, the full block for a block course.
        BlockSpec block = p.s(PartParams.SHAPE).equals(SHAPE_SLAB)
                ? BlockForms.slab(ctx.palette().slab(p.s(PartParams.MATERIAL), node), false)
                : ctx.plainBlock(p.s(PartParams.MATERIAL), node);
        for (int k = 0; k < length; k++) {
            ctx.emitAbs(node, wall.cell(horizontal ? a.u() + k : a.u(), layer, horizontal ? a.v() : a.v() + k), block);
        }
    }
}
