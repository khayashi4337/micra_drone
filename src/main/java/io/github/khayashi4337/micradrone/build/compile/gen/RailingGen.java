package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;

/** A straight run of fence, one to three blocks high, along its direction. */
final class RailingGen implements PartGenerator {
    private static final String P_LENGTH = "length";
    private static final String P_DIR = "dir";
    private static final String P_HEIGHT = "height";
    private static final String P_MATERIAL = "material";

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        Facing dir = Dirs.of(p.s(P_DIR));
        int length = p.i(P_LENGTH);
        int height = p.i(P_HEIGHT);
        BlockSpec fence = ctx.plainBlock(p.s(P_MATERIAL), node);
        for (int i = 0; i < length; i++) {
            for (int dv = 0; dv < height; dv++) {
                ctx.emit(node, i * dir.du(), dv, i * dir.dw(), fence);
            }
        }
    }
}
