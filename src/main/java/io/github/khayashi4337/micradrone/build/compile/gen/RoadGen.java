package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;

/** A flat strip: its length runs along the direction, its width to the right-hand side of that direction. */
final class RoadGen implements PartGenerator {
    private static final String P_LENGTH = "length";
    private static final String P_WIDTH = "width";
    private static final String P_DIR = "dir";
    private static final String P_MATERIAL = "material";

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        Facing dir = Dirs.of(p.s(P_DIR));
        Facing right = Dirs.right(dir);
        int length = p.i(P_LENGTH);
        int width = p.i(P_WIDTH);
        BlockSpec block = ctx.plainBlock(p.s(P_MATERIAL), node);
        for (int i = 0; i < length; i++) {
            for (int j = 0; j < width; j++) {
                ctx.emit(node, i * dir.du() + j * right.du(), 0, i * dir.dw() + j * right.dw(), block);
            }
        }
    }
}
