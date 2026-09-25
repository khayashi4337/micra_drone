package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;

/** A straight run of blocks along one local axis; a log's grain follows the run. */
final class BeamGen implements PartGenerator {
    private static final String P_AXIS = "axis";
    private static final String P_LENGTH = "length";
    private static final String P_MATERIAL = "material";
    // The axis parameter names a local axis; the block state names the same axis in the game's terms.
    private static final String AXIS_U = "u";
    private static final String AXIS_V = "v";
    private static final String AXIS_W = "w";
    private static final String STATE_AXIS_X = "x";
    private static final String STATE_AXIS_Y = "y";
    private static final String STATE_AXIS_Z = "z";

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        String axis = p.s(P_AXIS);
        String id = ctx.palette().full(p.s(P_MATERIAL), node);
        BlockSpec block = BlockForms.isAxisBlock(id) ? BlockForms.axisBlock(id, stateAxis(axis)) : BlockForms.plain(id);
        int length = p.i(P_LENGTH);
        for (int i = 0; i < length; i++) {
            ctx.emit(node, along(axis, AXIS_U, i), along(axis, AXIS_V, i), along(axis, AXIS_W, i), block);
        }
    }

    /** The offset on {@code thisAxis} after {@code steps} steps along {@code axis}: all of it, or none. */
    private static int along(String axis, String thisAxis, int steps) {
        return axis.equals(thisAxis) ? steps : 0;
    }

    private static String stateAxis(String axis) {
        return switch (axis) {
            case AXIS_U -> STATE_AXIS_X;
            case AXIS_V -> STATE_AXIS_Y;
            default -> STATE_AXIS_Z;
        };
    }
}
