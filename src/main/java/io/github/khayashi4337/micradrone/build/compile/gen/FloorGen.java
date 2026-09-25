package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;
import java.util.List;

/** The floor of one storey over the whole footprint, minus the rectangular holes (for stairs and ladders). */
final class FloorGen implements PartGenerator {
    private static final String P_LEVEL = "level";
    private static final String P_HOLES = "holes";
    private static final String P_KIND = "kind";
    private static final String P_MATERIAL = "material";
    private static final String KIND_SLAB = "slab";
    /** A hole is written as four numbers: two opposite corners (u0, w0, u1, w1). */
    private static final int VALUES_PER_HOLE = 4;
    private static final int U0 = 0;
    private static final int W0 = 1;
    private static final int U1 = 2;
    private static final int W1 = 3;

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        StructureInfo st = ctx.structureOf(node);
        int level = p.i(P_LEVEL);
        ctx.checkLevel(node, st, level);
        List<Integer> holes = p.ints(P_HOLES);
        if (holes.size() % VALUES_PER_HOLE != 0) {
            throw ctx.fail(node, IssueCode.E_PARAM_RANGE, P_HOLES, "holesは[u0,w0,u1,w1,…]の形で、" + VALUES_PER_HOLE
                    + "つずつの組にしてください(" + holes.size() + "個あります)");
        }
        boolean slab = p.s(P_KIND).equals(KIND_SLAB);
        String id = slab ? ctx.palette().slab(p.s(P_MATERIAL), node) : ctx.palette().full(p.s(P_MATERIAL), node);
        BlockSpec block = slab ? BlockForms.slab(id, false) : BlockForms.plain(id);
        int v = st.origin().v() + level * st.floorHeight();
        for (int u = 0; u < st.width(); u++) {
            for (int w = 0; w < st.depth(); w++) {
                if (inHole(holes, u, w)) {
                    ctx.canvas().charge();
                } else {
                    ctx.emitAbs(node, new LocalPos(st.origin().u() + u, v, st.origin().w() + w), block);
                }
            }
        }
    }

    private static boolean inHole(List<Integer> holes, int u, int w) {
        for (int k = 0; k < holes.size(); k += VALUES_PER_HOLE) {
            int u0 = Math.min(holes.get(k + U0), holes.get(k + U1));
            int u1 = Math.max(holes.get(k + U0), holes.get(k + U1));
            int w0 = Math.min(holes.get(k + W0), holes.get(k + W1));
            int w1 = Math.max(holes.get(k + W0), holes.get(k + W1));
            if (u >= u0 && u <= u1 && w >= w0 && w <= w1) {
                return true;
            }
        }
        return false;
    }
}
