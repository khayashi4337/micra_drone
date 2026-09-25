package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;
import java.util.Map;

/** Every cell of a wall: along it, through its thickness, up its height. Corners merge with the neighbouring wall. */
final class WallGen implements PartGenerator {
    private static final String P_MATERIAL = "material";

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        WallInfo wall = ctx.wallInfo(node.id()).orElse(null);
        if (wall == null) {
            return; // the reason was reported when the wall's geometry was built
        }
        BlockSpec block = ctx.plainBlock(p.s(P_MATERIAL), node);
        String mergeGroup = wall.cornerGroup();
        for (int i = 0; i < wall.length(); i++) {
            for (int layer = 0; layer < wall.thickness(); layer++) {
                for (int row = 0; row < wall.height(); row++) {
                    ctx.emitAbs(node, wall.cell(i, layer, row), block, mergeGroup, wall.side().lower(), Map.of());
                }
            }
        }
    }
}
