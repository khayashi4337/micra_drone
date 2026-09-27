package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;

/** A column of shaft with an optional base at the foot and capital at the top, both of the trim material. */
final class PillarGen implements PartGenerator {
    private static final String P_HEIGHT = "height";
    private static final String P_BASE = "base";
    private static final String P_CAPITAL = "capital";
    private static final String P_MATERIAL = "material";
    private static final String ROLE_TRIM = "trim";
    /** A one-block pillar is all shaft: its only block cannot be an end as well. */
    private static final int SHAFT_ONLY_HEIGHT = 1;

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        int height = p.i(P_HEIGHT);
        boolean hasEnds = height > SHAFT_ONLY_HEIGHT;
        boolean hasBase = hasEnds && p.b(P_BASE);
        boolean hasCapital = hasEnds && p.b(P_CAPITAL);
        // Every row's block is asked of the palette before the first row is placed, so a refused material leaves no row
        // behind. Each row asks for its own material, so only a block that is placed is asked for and checked.
        BlockSpec[] rows = new BlockSpec[height];
        for (int dv = 0; dv < height; dv++) {
            boolean trim = (hasBase && dv == 0) || (hasCapital && dv == height - 1);
            rows[dv] = ctx.plainBlock(trim ? ROLE_TRIM : p.s(P_MATERIAL), node);
        }
        for (int dv = 0; dv < height; dv++) {
            ctx.emit(node, 0, dv, 0, rows[dv]);
        }
    }
}
