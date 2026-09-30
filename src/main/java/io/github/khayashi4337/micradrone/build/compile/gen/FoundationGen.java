package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;
import io.github.khayashi4337.micradrone.build.parts.PartParams;

/** A solid block under the building's footprint, widened by {@code margin} and {@code depth} rows deep. */
final class FoundationGen implements PartGenerator {
    /** The foundation's top row is the one just under the ground floor (v = -1). */
    private static final int TOP_ROW = -1;

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        StructureInfo st = ctx.structureOf(node);
        int margin = p.i(PartParams.MARGIN);
        int depth = p.i(PartParams.DEPTH);
        BlockSpec block = ctx.plainBlock(p.s(PartParams.MATERIAL), node);
        for (int u = -margin; u <= st.width() - 1 + margin; u++) {
            for (int w = -margin; w <= st.depth() - 1 + margin; w++) {
                for (int v = -depth; v <= TOP_ROW; v++) {
                    ctx.emitAbs(node, st.origin().plus(u, v, w), block);
                }
            }
        }
    }
}
