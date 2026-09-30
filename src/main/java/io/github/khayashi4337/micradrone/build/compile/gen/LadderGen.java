package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;
import io.github.khayashi4337.micradrone.build.parts.PartParams;

/** A column of ladders that all face the same way. */
final class LadderGen implements PartGenerator {

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        BlockSpec ladder = BlockForms.ladder(Dirs.of(p.s(PartParams.FACING)));
        int height = p.i(PartParams.HEIGHT);
        for (int dv = 0; dv < height; dv++) {
            ctx.emit(node, 0, dv, 0, ladder);
        }
    }
}
