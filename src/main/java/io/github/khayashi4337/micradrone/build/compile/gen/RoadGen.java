package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;
import io.github.khayashi4337.micradrone.build.parts.PartParams;

/** A flat strip: its length runs along the direction, its width to the right-hand side of that direction. */
final class RoadGen implements PartGenerator {

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        Facing dir = Dirs.of(p.s(PartParams.DIR));
        int length = p.i(PartParams.LENGTH);
        int width = p.i(PartParams.WIDTH);
        BlockSpec block = ctx.plainBlock(p.s(PartParams.MATERIAL), node);
        for (int i = 0; i < length; i++) {
            for (int j = 0; j < width; j++) {
                Dirs.Offset o = Dirs.offset(dir, i, j);
                ctx.emit(node, o.u(), 0, o.w(), block);
            }
        }
    }
}
