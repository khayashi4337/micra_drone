package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.parts.Params;

/**
 * A floor that projects out of a wall, with a fence along its outer three sides. The anchor's u is counted from the
 * wall's start and its v from the storey's floor, which is the row just below the wall's lowest row. The floor lies
 * along the wall it is attached to: a width that runs past the wall's end is refused.
 */
final class BalconyGen implements PartGenerator {
    private static final String P_WIDTH = "width";
    private static final String P_DEPTH = "depth";
    private static final String P_RAIL = "rail";
    private static final String P_MATERIAL = "material";
    private static final String ROLE_FENCE = "fence";
    /** The fence stands on the floor. */
    private static final int RAIL_ABOVE_FLOOR = 1;
    /** The first cell out from the wall: layer -1 of a wall is the first cell outside its outermost layer. */
    private static final int FIRST_CELL_OUT = 1;

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        WallInfo wall = ctx.wallOfAnchor(node); // refuses anything but an OnSurface anchor on a wall, so the cast holds
        Anchor.OnSurface a = (Anchor.OnSurface) node.anchor();
        if (a.side() != Side.OUTER) {
            throw ctx.fail(node, IssueCode.E_ANCHOR, GenContext.KEY_ANCHOR, "バルコニーは、壁の外側(outer)にだけ付けられます");
        }
        int width = p.i(P_WIDTH);
        // The floor lies along the wall it is attached to. Checked before anything is looked up or placed, so a refusal
        // leaves no cell behind.
        ctx.requireAlongWall(node, wall, a.u(), width, "バルコニー");
        int depth = p.i(P_DEPTH);
        boolean rail = p.b(P_RAIL);
        int firstI = a.u();
        int lastI = a.u() + width - 1;
        int floorRow = wall.rowAboveStoreyFloor(a.v());
        // Every block that will be placed is asked of the palette before the first one goes down (a refused material
        // leaves no cell behind), and only those that will be placed: the fence role is asked for by a railed balcony only.
        BlockSpec floor = ctx.plainBlock(p.s(P_MATERIAL), node);
        BlockSpec fence = rail ? ctx.plainBlock(ROLE_FENCE, node) : null;
        for (int i = firstI; i <= lastI; i++) {
            for (int k = FIRST_CELL_OUT; k <= depth; k++) {
                ctx.emitAbs(node, wall.cell(i, -k, floorRow), floor);
            }
        }
        if (rail) {
            for (int i = firstI; i <= lastI; i++) {
                for (int k = FIRST_CELL_OUT; k <= depth; k++) {
                    boolean onOuterEdge = k == depth || i == firstI || i == lastI;
                    if (onOuterEdge) {
                        ctx.emitAbs(node, wall.cell(i, -k, floorRow + RAIL_ABOVE_FLOOR), fence);
                    } else {
                        ctx.canvas().charge(); // the interior of the floor is considered and left without a fence
                    }
                }
            }
        }
    }
}
