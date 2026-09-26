package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.parts.Params;

/**
 * A decorative course along a wall face: a run of blocks or bottom slabs, along the wall (horizontal) or up it
 * (vertical), on the first cell outside the wall (OUTER) or the first cell inside (INNER). The course lies on the
 * wall it is attached to: a length that runs past the wall's end or top is refused.
 */
final class TrimGen implements PartGenerator {
    private static final String P_LENGTH = "length";
    private static final String P_AXIS = "axis";
    private static final String P_SHAPE = "shape";
    private static final String P_MATERIAL = "material";
    private static final String AXIS_HORIZONTAL = "horizontal";
    private static final String SHAPE_SLAB = "slab";
    /** {@code layer} -1 of a wall is the first cell outside its outermost layer; {@code thickness} is just inside. */
    private static final int OUTSIDE_LAYER = -1;

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        WallInfo wall = ctx.wallOfAnchor(node); // refuses anything but an OnSurface anchor on a wall, so the cast holds
        Anchor.OnSurface a = (Anchor.OnSurface) node.anchor();
        int layer = a.side() == Side.OUTER ? OUTSIDE_LAYER : wall.thickness();
        int length = p.i(P_LENGTH);
        boolean horizontal = p.s(P_AXIS).equals(AXIS_HORIZONTAL);
        // The course lies on the wall it is attached to. Checked before anything is looked up or placed, so a refusal
        // leaves no cell behind. The anchor's own cell is already on the wall, so only the far end can be past it.
        boolean fits = horizontal ? wall.fitsAlong(a.u(), length) : wall.fitsUp(a.v(), length);
        if (!fits) {
            throw ctx.fail(node, IssueCode.E_ANCHOR, GenContext.KEY_EXTENT, node.id() + "の縁取り("
                    + (horizontal ? "u=" + a.u() : "v=" + a.v()) + "から長さ" + length + ")が、壁(" + wall.id()
                    + ")の外にはみ出しています(壁は長さ" + wall.length() + "、高さ" + wall.height() + ")");
        }
        // Asked of the palette before the first cell goes down, and only the form that is placed: the slab form of
        // the material for a slab course, the full block for a block course.
        BlockSpec block = p.s(P_SHAPE).equals(SHAPE_SLAB)
                ? BlockForms.slab(ctx.palette().slab(p.s(P_MATERIAL), node), false)
                : ctx.plainBlock(p.s(P_MATERIAL), node);
        for (int k = 0; k < length; k++) {
            ctx.emitAbs(node, wall.cell(horizontal ? a.u() + k : a.u(), layer, horizontal ? a.v() : a.v() + k), block);
        }
    }
}
