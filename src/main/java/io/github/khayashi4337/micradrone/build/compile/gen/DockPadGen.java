package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;

/** A landing pad with an optional marked edge, a cargo barrel on it, and clear air above. */
final class DockPadGen implements PartGenerator {
    private static final String P_WIDTH = "width";
    private static final String P_DEPTH = "depth";
    private static final String P_CLEARANCE = "clearance";
    private static final String P_CARGO_U = "cargo_u";
    private static final String P_CARGO_W = "cargo_w";
    private static final String P_MARKER = "marker";
    private static final String P_MATERIAL = "material";
    private static final String ROLE_MARKER = "marker";
    private static final String ROLE_CARGO = "cargo";
    /** How the extent that a cargo position must stay inside is named in the message. */
    private static final String EXTENT_WIDTH = "幅";
    private static final String EXTENT_DEPTH = "奥行";
    private static final String FACING_UP = "up";
    /** The Issue.data reason of an overlap that is a breach of the air space and not a shared cell. */
    private static final String REASON_CLEARANCE = "clearance";
    /** The row just above the pad: the cargo barrel stands on it, and the air space that must stay clear starts there. */
    private static final int ROW_ABOVE_PAD = 1;

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        int width = p.i(P_WIDTH);
        int depth = p.i(P_DEPTH);
        int cargoU = p.i(P_CARGO_U);
        int cargoW = p.i(P_CARGO_W);
        requireOnPad(ctx, node, P_CARGO_U, cargoU, EXTENT_WIDTH, width);
        requireOnPad(ctx, node, P_CARGO_W, cargoW, EXTENT_DEPTH, depth);
        BlockSpec pad = ctx.plainBlock(p.s(P_MATERIAL), node);
        // Without the marker the edge is pad too, and the marker role is not asked of the palette (so it is not checked).
        BlockSpec edge = p.b(P_MARKER) ? ctx.plainBlock(ROLE_MARKER, node) : pad;
        BlockSpec cargo = BlockSpec.of(ctx.palette().full(ROLE_CARGO, node), BlockForms.PROP_FACING, FACING_UP);
        for (int u = 0; u < width; u++) {
            for (int w = 0; w < depth; w++) {
                ctx.emit(node, u, 0, w, isEdge(u, w, width, depth) ? edge : pad);
            }
        }
        ctx.emit(node, cargoU, ROW_ABOVE_PAD, cargoW, cargo);
    }

    private static boolean isEdge(int u, int w, int width, int depth) {
        return u == 0 || w == 0 || u == width - 1 || w == depth - 1;
    }

    private static void requireOnPad(GenContext ctx, PlanNode node, String param, int value, String extentName, int extent) {
        if (value >= extent) {
            throw ctx.fail(node, IssueCode.E_PARAM_RANGE, param,
                    "荷役口(" + param + "=" + value + ")が、台の" + extentName + "(" + extent + ")の外です");
        }
    }

    /**
     * The air above the pad (from the row above it up to the clearance) belongs to the pad: nothing else may stand in
     * it, except the cargo barrel. Every cell looked at counts as work, since most of them are empty and place nothing.
     */
    @Override
    public void afterAll(GenContext ctx, PlanNode node, Params p) {
        if (ctx.info(node.id()).origin() == null) {
            return; // a pad with no position was refused by generate; its air space is nowhere
        }
        int width = p.i(P_WIDTH);
        int depth = p.i(P_DEPTH);
        int clearance = p.i(P_CLEARANCE);
        for (int u = 0; u < width; u++) {
            for (int w = 0; w < depth; w++) {
                for (int dv = ROW_ABOVE_PAD; dv <= clearance; dv++) {
                    ctx.canvas().charge();
                    LocalPos pos = ctx.placed(node, u, dv, w);
                    Canvas.Cell c = ctx.canvas().get(pos);
                    if (c != null && !c.ownerId().equals(node.id())) {
                        ctx.canvas().recordOverlap(node.id(), c.ownerId(), pos, REASON_CLEARANCE);
                    }
                }
            }
        }
    }
}
