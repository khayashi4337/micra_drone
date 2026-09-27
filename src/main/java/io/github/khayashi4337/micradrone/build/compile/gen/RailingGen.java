package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;
import io.github.khayashi4337.micradrone.build.parts.PartParams;

/** A straight run of fence, one to three blocks high, along its direction. */
final class RailingGen implements PartGenerator {

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        Facing dir = Dirs.of(p.s(PartParams.DIR));
        int length = p.i(PartParams.LENGTH);
        int height = p.i(PartParams.HEIGHT);
        BlockSpec fence = ctx.plainBlock(p.s(PartParams.MATERIAL), node);
        for (int i = 0; i < length; i++) {
            for (int dv = 0; dv < height; dv++) {
                ctx.emit(node, i * dir.du(), dv, i * dir.dw(), fence);
            }
        }
    }
}
