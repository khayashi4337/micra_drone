package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
    /** One step along the pad's own u or w, to see where the node's turn and mirror send it. */
    private static final int ONE_STEP = 1;

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
     * it, except the cargo barrel. Only a pad that generate made gets here (a refused pad has no air space).
     *
     * <p>Most of the air is empty, so it is the occupied cells that are looked at, never the air. Whichever is fewer is
     * walked: the air's cells, asking the canvas for each (the box is small, or the canvas is large), or the canvas's
     * cells, asking each whether it lies in the air. The cost is at most the smaller of the two, and the work budget is
     * charged one unit per occupied cell looked at (the air cells that turn out empty are free). Two pads of the largest
     * size that the spec allows therefore fit the default budget: each costs the cells on the canvas, not its 262,144
     * cells of air. Both ways record the overlaps in the same order, the pad's own u, then w, then height, so the first
     * cell named in an issue does not depend on the way.
     */
    @Override
    public void afterAll(GenContext ctx, PlanNode node, Params p) {
        int width = p.i(P_WIDTH);
        int depth = p.i(P_DEPTH);
        int clearance = p.i(P_CLEARANCE);
        long airCells = (long) width * depth * clearance;
        List<Canvas.Cell> intruders = airCells <= ctx.canvas().size()
                ? occupiedAmongTheAirCells(ctx, node, width, depth, clearance)
                : occupiedAmongTheCanvasCells(ctx, node, width, depth, clearance);
        for (Canvas.Cell c : intruders) {
            ctx.canvas().recordOverlap(node.id(), c.ownerId(), c.pos(), REASON_CLEARANCE);
        }
    }

    /** The cells of other parts in the air, in the pad's u, w, height order; each occupied cell asked for is charged. */
    private static List<Canvas.Cell> occupiedAmongTheAirCells(GenContext ctx, PlanNode node, int width, int depth, int clearance) {
        List<Canvas.Cell> out = new ArrayList<>();
        for (int u = 0; u < width; u++) {
            for (int w = 0; w < depth; w++) {
                for (int dv = ROW_ABOVE_PAD; dv <= clearance; dv++) {
                    Canvas.Cell c = ctx.canvas().get(ctx.placed(node, u, dv, w));
                    if (c != null) {
                        ctx.canvas().charge();
                        if (!c.ownerId().equals(node.id())) {
                            out.add(c);
                        }
                    }
                }
            }
        }
        return out;
    }

    /** A cell of another part found in the air, with where it lies in the pad's own u, w and height. */
    private record Intruder(Canvas.Cell cell, int u, int w, int dv) {
    }

    /** The cells of other parts in the air, in the pad's u, w, height order; every canvas cell looked at is charged. */
    private static List<Canvas.Cell> occupiedAmongTheCanvasCells(GenContext ctx, PlanNode node, int width, int depth, int clearance) {
        // The air is a box on the canvas: the image of the pad's box under the node's turn and mirror, which only
        // exchange and flip axes, so the images of two opposite corners bound it. The pad's own u and w of a cell come
        // from where the pad's +u and +w step went.
        LocalPos origin = ctx.placed(node, 0, 0, 0);
        LocalPos uStep = ctx.placed(node, ONE_STEP, 0, 0);
        LocalPos wStep = ctx.placed(node, 0, 0, ONE_STEP);
        LocalPos near = ctx.placed(node, 0, ROW_ABOVE_PAD, 0);
        LocalPos far = ctx.placed(node, width - 1, clearance, depth - 1);
        Box air = Box.of(near.u(), near.v(), near.w(), far.u(), far.v(), far.w());
        List<Intruder> found = new ArrayList<>();
        for (Canvas.Cell c : ctx.canvas().all()) {
            ctx.canvas().charge();
            LocalPos pos = c.pos();
            if (!c.ownerId().equals(node.id()) && air.contains(pos.u(), pos.v(), pos.w())) {
                found.add(new Intruder(c, along(pos, origin, uStep), along(pos, origin, wStep), pos.v() - origin.v()));
            }
        }
        found.sort(Comparator.comparingInt(Intruder::u).thenComparingInt(Intruder::w).thenComparingInt(Intruder::dv));
        return found.stream().map(Intruder::cell).toList();
    }

    /** How far {@code pos} lies from {@code origin} along the axis that {@code step} (a unit step from the origin) points. */
    private static int along(LocalPos pos, LocalPos origin, LocalPos step) {
        return (pos.u() - origin.u()) * (step.u() - origin.u()) + (pos.v() - origin.v()) * (step.v() - origin.v())
                + (pos.w() - origin.w()) * (step.w() - origin.w());
    }
}
