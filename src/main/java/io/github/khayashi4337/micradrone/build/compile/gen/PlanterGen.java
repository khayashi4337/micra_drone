package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.parts.Params;

/**
 * A flower bed along the outside of a wall: a row of soil on the first cell outside the wall, with a plant on
 * top of each. The bed lies along the wall it is attached to: a width that runs past the wall's end is refused.
 */
final class PlanterGen implements PartGenerator {
    private static final String P_WIDTH = "width";
    /** The palette roles of the soil row and of the plants on it. */
    private static final String ROLE_PLANTER = "planter";
    private static final String ROLE_PLANT = "plant";
    /** The plant stands on the soil. */
    private static final int PLANT_ABOVE_SOIL = 1;

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        WallInfo wall = ctx.wallOfAnchor(node); // refuses anything but an OnSurface anchor on a wall, so the cast holds
        Anchor.OnSurface a = (Anchor.OnSurface) node.anchor();
        if (a.side() != Side.OUTER) {
            throw ctx.fail(node, IssueCode.E_ANCHOR, GenContext.KEY_ANCHOR, "植栽は、壁の外側(outer)にだけ付けられます");
        }
        int width = p.i(P_WIDTH);
        // The bed lies along the wall it is attached to. Checked before anything is looked up or placed, so a refusal
        // leaves no cell behind.
        ctx.requireAlongWall(node, wall, a.u(), width, "植栽");
        // Every block that will be placed is asked of the palette before the first one goes down.
        BlockSpec soil = ctx.plainBlock(ROLE_PLANTER, node);
        BlockSpec plant = ctx.plainBlock(ROLE_PLANT, node);
        for (int i = a.u(); i < a.u() + width; i++) {
            ctx.emitAbs(node, wall.cell(i, WallInfo.OUTSIDE_LAYER, a.v()), soil);
            ctx.emitAbs(node, wall.cell(i, WallInfo.OUTSIDE_LAYER, a.v() + PLANT_ABOVE_SOIL), plant);
        }
    }
}
