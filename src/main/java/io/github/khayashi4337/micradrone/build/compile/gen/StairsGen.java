package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;
import io.github.khayashi4337.micradrone.build.parts.PartParams;

/**
 * A staircase that climbs one block per step in its direction, its width to the right-hand side of that direction. The
 * stairs have their tall backs towards the direction, so a walker heading that way climbs them.
 */
final class StairsGen implements PartGenerator {

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        Facing dir = Dirs.of(p.s(PartParams.DIR));
        int steps = p.i(PartParams.STEPS);
        int width = p.i(PartParams.WIDTH);
        BlockSpec step = BlockForms.stairs(ctx.palette().stairs(p.s(PartParams.MATERIAL), node), dir, false);
        for (int s = 0; s < steps; s++) {
            for (int j = 0; j < width; j++) {
                Dirs.Offset o = Dirs.offset(dir, s, j);
                ctx.emit(node, o.u(), s, o.w(), step);
            }
        }
    }
}
