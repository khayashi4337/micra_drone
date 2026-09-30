package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;
import io.github.khayashi4337.micradrone.build.parts.PartParams;

/** A straight run of blocks along one local axis; a log's grain follows the run. */
final class BeamGen implements PartGenerator {
    // The axis parameter names a local axis; the block state names the same axis in the game's terms.
    private static final String STATE_AXIS_X = "x";
    private static final String STATE_AXIS_Y = "y";
    private static final String STATE_AXIS_Z = "z";

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        String axis = p.s(PartParams.AXIS);
        String id = ctx.palette().full(p.s(PartParams.MATERIAL), node);
        BlockSpec block = BlockForms.isAxisBlock(id) ? BlockForms.axisBlock(id, stateAxis(axis)) : BlockForms.plain(id);
        int length = p.i(PartParams.LENGTH);
        for (int i = 0; i < length; i++) {
            ctx.emit(node, along(axis, PartParams.AXIS_U, i), along(axis, PartParams.AXIS_V, i), along(axis, PartParams.AXIS_W, i), block);
        }
    }

    /** The offset on {@code thisAxis} after {@code steps} steps along {@code axis}: all of it, or none. */
    private static int along(String axis, String thisAxis, int steps) {
        return axis.equals(thisAxis) ? steps : 0;
    }

    private static String stateAxis(String axis) {
        return switch (axis) {
            case PartParams.AXIS_U -> STATE_AXIS_X;
            case PartParams.AXIS_V -> STATE_AXIS_Y;
            default -> STATE_AXIS_Z;
        };
    }
}
