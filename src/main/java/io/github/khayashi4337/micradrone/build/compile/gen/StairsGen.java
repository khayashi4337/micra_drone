package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;

/**
 * A staircase that climbs one block per step in its direction, its width to the right-hand side of that direction. The
 * stairs have their tall backs towards the direction, so a walker heading that way climbs them.
 */
final class StairsGen implements PartGenerator {
    private static final String P_STEPS = "steps";
    private static final String P_WIDTH = "width";
    private static final String P_DIR = "dir";
    private static final String P_MATERIAL = "material";

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        Facing dir = Dirs.of(p.s(P_DIR));
        int steps = p.i(P_STEPS);
        int width = p.i(P_WIDTH);
        BlockSpec step = BlockForms.stairs(ctx.palette().stairs(p.s(P_MATERIAL), node), dir, false);
        for (int s = 0; s < steps; s++) {
            for (int j = 0; j < width; j++) {
                ctx.emit(node, Dirs.du(dir, s, j), s, Dirs.dw(dir, s, j), step);
            }
        }
    }
}
