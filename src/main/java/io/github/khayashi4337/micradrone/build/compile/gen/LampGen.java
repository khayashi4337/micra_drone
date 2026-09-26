package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;

/**
 * A light: a lantern standing or hanging, a torch, or a fence post with a lantern on top. The part stands alone
 * (an Absolute anchor); {@code height} is the pole's height in fence blocks and only the post variant uses it.
 */
final class LampGen implements PartGenerator {
    private static final String P_KIND = "kind";
    private static final String P_HEIGHT = "height";
    private static final String KIND_LANTERN = "lantern";
    private static final String KIND_HANGING = "hanging";
    private static final String KIND_TORCH = "torch";
    private static final String KIND_POST = "post";
    /** The palette role of the post's pole. */
    private static final String ROLE_FENCE = "fence";
    private static final String TORCH = "minecraft:torch";

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        switch (p.s(P_KIND)) {
            case KIND_LANTERN -> ctx.emit(node, 0, 0, 0, BlockForms.lantern(false));
            case KIND_HANGING -> ctx.emit(node, 0, 0, 0, BlockForms.lantern(true));
            case KIND_TORCH -> ctx.emit(node, 0, 0, 0, BlockForms.plain(TORCH));
            case KIND_POST -> {
                // Asked of the palette before the first cell goes down (a refused role leaves no cell behind),
                // and only in this variant: the other kinds never look the fence role up.
                BlockSpec fence = ctx.plainBlock(ROLE_FENCE, node);
                int height = p.i(P_HEIGHT);
                for (int dv = 0; dv < height; dv++) {
                    ctx.emit(node, 0, dv, 0, fence);
                }
                ctx.emit(node, 0, height, 0, BlockForms.lantern(false));
            }
            default -> throw ctx.fail(node, IssueCode.E_PARAM_RANGE, P_KIND, "照明の種類が不明です");
        }
    }
}
