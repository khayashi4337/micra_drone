package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;

/** A column of ladders that all face the same way. */
final class LadderGen implements PartGenerator {
    private static final String P_HEIGHT = "height";
    private static final String P_FACING = "facing";

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        BlockSpec ladder = BlockForms.ladder(Dirs.of(p.s(P_FACING)));
        int height = p.i(P_HEIGHT);
        for (int dv = 0; dv < height; dv++) {
            ctx.emit(node, 0, dv, 0, ladder);
        }
    }
}
