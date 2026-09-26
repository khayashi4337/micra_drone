package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;
import java.util.function.IntUnaryOperator;

/**
 * The roof kinds of design 05 §1.1.1, laid on top of the building's last storey with a 1:1 pitch of bottom-half stairs.
 * Each kind looks up every block it will place before its first cell, and only those: a refused material then leaves
 * no partial roof behind (no overlap noise), and a role the kind does not place is never recorded as used.
 */
final class RoofGen implements PartGenerator {
    private static final String P_KIND = "kind";
    private static final String P_OVERHANG = "overhang";
    private static final String P_RIDGE = "ridge";
    private static final String P_HIGH_SIDE = "high_side";
    private static final String P_GABLE_FILL = "gable_fill";
    private static final String P_TOOTH = "tooth";
    private static final String P_MONITOR_WIDTH = "monitor_width";
    private static final String P_MONITOR_HEIGHT = "monitor_height";
    private static final String P_MATERIAL = "material";

    private static final String KIND_GABLE = "gable";
    private static final String KIND_HIP = "hip";
    private static final String KIND_FLAT = "flat";
    private static final String KIND_SHED = "shed";
    private static final String KIND_SAWTOOTH = "sawtooth";
    private static final String KIND_MONITOR = "monitor";

    private static final String RIDGE_W = "w";
    private static final String RIDGE_AUTO = "auto";
    private static final String ROLE_WALL = "wall";
    private static final String ROLE_GLASS = "glass";

    /** Roof stairs and slabs are never upside down: they sit on the bottom half of their cell. */
    private static final boolean UPSIDE_DOWN = false;
    /** A section has two slopes that meet in the middle: every layer steps one cell in from each side. */
    private static final int SLOPES = 2;

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        StructureInfo st = ctx.structureOf(node);
        int overhang = p.i(P_OVERHANG);
        int base = st.origin().v() + st.totalHeight();
        String ridge = p.s(P_RIDGE);
        boolean ridgeAlongW = ridge.equals(RIDGE_W) || (ridge.equals(RIDGE_AUTO) && st.depth() >= st.width());
        RoofFrame f = frame(st, ridgeAlongW, overhang);
        // hip and flat are symmetric in u and w: they read the frame with a=u, b=w
        RoofFrame uw = frame(st, true, overhang);
        String material = p.s(P_MATERIAL);
        switch (p.s(P_KIND)) {
            case KIND_GABLE -> gable(ctx, node, p, st, f, base, material);
            case KIND_HIP -> hip(ctx, node, uw, base, material);
            case KIND_FLAT -> flat(ctx, node, uw, base, material);
            case KIND_SHED -> shed(ctx, node, p, st, overhang, base, material);
            case KIND_SAWTOOTH -> sawtooth(ctx, node, p, f, base, material);
            case KIND_MONITOR -> monitor(ctx, node, p, st, f, base, material);
            default -> throw ctx.fail(node, IssueCode.E_PARAM_RANGE, P_KIND, "屋根の種類が不明です");
        }
    }

    /** The building's footprint grown by {@code overhang} on every side, seen through a roof frame. */
    private static RoofFrame frame(StructureInfo st, boolean ridgeAlongW, int overhang) {
        LocalPos org = st.origin();
        int u0 = org.u() - overhang;
        int u1 = org.u() + st.width() - 1 + overhang;
        int w0 = org.w() - overhang;
        int w1 = org.w() + st.depth() - 1 + overhang;
        return ridgeAlongW ? new RoofFrame(true, u0, u1, w0, w1) : new RoofFrame(false, w0, w1, u0, u1);
    }

    /** The building's own footprint (no overhang) in the same axes as {@code f}: its b edges are the gable end faces. */
    private static RoofFrame footprint(StructureInfo st, RoofFrame f) {
        return frame(st, f.ridgeAlongW(), 0);
    }

    private static int[] endFaces(RoofFrame footprint) {
        return new int[]{footprint.b0(), footprint.b1()};
    }

    /** One block at column {@code a} and height {@code v}, all along the ridge. */
    private static void row(GenContext ctx, PlanNode node, RoofFrame f, int a, int v, BlockSpec block) {
        for (int b = f.b0(); b <= f.b1(); b++) {
            ctx.emitAbs(node, f.pos(a, v, b), block);
        }
    }

    /** {@code layers} layers of stair rows from both edges, each leaning toward the middle. */
    private static void slopes(GenContext ctx, PlanNode node, RoofFrame f, int base, int layers, String stairsId) {
        BlockSpec low = BlockForms.stairs(stairsId, f.towardRidgeFromLow(), UPSIDE_DOWN);
        BlockSpec high = BlockForms.stairs(stairsId, f.towardRidgeFromHigh(), UPSIDE_DOWN);
        for (int k = 0; k < layers; k++) {
            row(ctx, node, f, f.a0() + k, base + k, low);
            row(ctx, node, f, f.a1() - k, base + k, high);
        }
    }

    /** Stair rows from both edges meeting at the ridge; an odd span leaves one middle column for a row of full blocks. */
    private static void gable(GenContext ctx, PlanNode node, Params p, StructureInfo st, RoofFrame f, int base, String material) {
        int span = f.a1() - f.a0() + 1;
        int layers = span / SLOPES;
        boolean hasRidgeRow = span % SLOPES != 0;
        String stairsId = ctx.palette().stairs(material, node);
        BlockSpec ridge = hasRidgeRow ? ctx.plainBlock(material, node) : null;
        BlockSpec wall = p.b(P_GABLE_FILL) ? ctx.plainBlock(ROLE_WALL, node) : null;
        slopes(ctx, node, f, base, layers, stairsId);
        if (ridge != null) {
            row(ctx, node, f, f.a0() + layers, base + layers, ridge);
        }
        if (wall != null) {
            fillEnds(ctx, node, st, f, base, a -> Math.min(a - f.a0(), f.a1() - a), wall);
        }
    }

    /** Fills the triangle under the slope at both ends of the ridge, inside the building's own footprint. */
    private static void fillEnds(GenContext ctx, PlanNode node, StructureInfo st, RoofFrame f, int base, IntUnaryOperator height,
                                 BlockSpec block) {
        RoofFrame inside = footprint(st, f);
        for (int b : endFaces(inside)) {
            for (int a = inside.a0(); a <= inside.a1(); a++) {
                int h = height.applyAsInt(a);
                if (h <= 0) {
                    ctx.canvas().charge(); // the eaves column is considered and left empty
                }
                for (int dv = 0; dv < h; dv++) {
                    ctx.emitAbs(node, f.pos(a, base + dv, b), block);
                }
            }
        }
    }

    /** Rings shrinking by one cell per layer; a ring that has collapsed into a line is full blocks. {@code f} has a=u, b=w. */
    private static void hip(GenContext ctx, PlanNode node, RoofFrame f, int base, String material) {
        int u0 = f.a0();
        int u1 = f.a1();
        int w0 = f.b0();
        int w1 = f.b1();
        // the last ring is a line exactly when the shorter side is odd, i.e. its last index difference is even
        boolean endsInALine = Math.min(u1 - u0, w1 - w0) % SLOPES == 0;
        String stairsId = ctx.palette().stairs(material, node);
        BlockSpec full = endsInALine ? ctx.plainBlock(material, node) : null;
        for (int k = 0; u0 + k <= u1 - k && w0 + k <= w1 - k; k++) {
            Ring ring = new Ring(u0 + k, u1 - k, w0 + k, w1 - k, base + k);
            BlockSpec lineBlock = ring.isLine() ? full : null;
            for (int u = ring.uL(); u <= ring.uR(); u++) {
                hipCell(ctx, node, ring, u, ring.wS(), stairsId, lineBlock);
                if (ring.wN() != ring.wS()) {
                    hipCell(ctx, node, ring, u, ring.wN(), stairsId, lineBlock);
                }
            }
            for (int w = ring.wS() + 1; w < ring.wN(); w++) {
                hipCell(ctx, node, ring, ring.uL(), w, stairsId, lineBlock);
                if (ring.uR() != ring.uL()) {
                    hipCell(ctx, node, ring, ring.uR(), w, stairsId, lineBlock);
                }
            }
        }
    }

    /** One layer of a hip roof: the cells on the edge of [uL,uR] x [wS,wN] at height v. */
    private record Ring(int uL, int uR, int wS, int wN, int v) {
        boolean isLine() {
            return uL == uR || wS == wN;
        }

        /** The stair's back leans inward; a corner leans east or west. */
        Facing back(int u, int w) {
            return u == uL ? Facing.EAST : u == uR ? Facing.WEST : w == wS ? Facing.NORTH : Facing.SOUTH;
        }
    }

    private static void hipCell(GenContext ctx, PlanNode node, Ring ring, int u, int w, String stairsId, BlockSpec lineBlock) {
        BlockSpec block = lineBlock != null ? lineBlock : BlockForms.stairs(stairsId, ring.back(u, w), UPSIDE_DOWN);
        ctx.emitAbs(node, new LocalPos(u, ring.v(), w), block);
    }

    private static void flat(GenContext ctx, PlanNode node, RoofFrame f, int base, String material) {
        BlockSpec slab = BlockForms.slab(ctx.palette().slab(material, node), UPSIDE_DOWN);
        for (int a = f.a0(); a <= f.a1(); a++) {
            row(ctx, node, f, a, base, slab);
        }
    }

    /** One slope rising one cell per column toward {@code high_side}, the stairs' backs toward that side. */
    private static void shed(GenContext ctx, PlanNode node, Params p, StructureInfo st, int overhang, int base, String material) {
        Facing high = Facing.parse(p.s(P_HIGH_SIDE));
        boolean acrossU = high == Facing.EAST || high == Facing.WEST;
        RoofFrame f = frame(st, acrossU, overhang);
        boolean risesWithA = high == Facing.EAST || high == Facing.NORTH;
        BlockSpec stairs = BlockForms.stairs(ctx.palette().stairs(material, node), high, UPSIDE_DOWN);
        BlockSpec wall = p.b(P_GABLE_FILL) ? ctx.plainBlock(ROLE_WALL, node) : null;
        IntUnaryOperator rise = a -> risesWithA ? a - f.a0() : f.a1() - a;
        for (int a = f.a0(); a <= f.a1(); a++) {
            row(ctx, node, f, a, base + rise.applyAsInt(a), stairs);
        }
        if (wall != null) {
            fillEnds(ctx, node, st, f, base, rise, wall);
        }
    }

    /** Teeth of {@code tooth} columns rising toward larger a, with a glass row above the top stair of each. */
    private static void sawtooth(GenContext ctx, PlanNode node, Params p, RoofFrame f, int base, String material) {
        int tooth = p.i(P_TOOTH);
        BlockSpec stairs = BlockForms.stairs(ctx.palette().stairs(material, node), f.towardRidgeFromLow(), UPSIDE_DOWN);
        BlockSpec glass = ctx.plainBlock(ROLE_GLASS, node);
        for (int a = f.a0(); a <= f.a1(); a++) {
            int idx = (a - f.a0()) % tooth;
            row(ctx, node, f, a, base + idx, stairs);
            boolean lastOfTooth = idx == tooth - 1 || a == f.a1();
            if (lastOfTooth) {
                row(ctx, node, f, a, base + idx + 1, glass);
            }
        }
    }

    /**
     * A gable cut off where the middle gap is {@code monitor_width} wide, with glass walls on the gap's two edge columns
     * and a slab lid over the whole gap.
     */
    private static void monitor(GenContext ctx, PlanNode node, Params p, StructureInfo st, RoofFrame f, int base, String material) {
        int span = f.a1() - f.a0() + 1;
        int gapWidth = p.i(P_MONITOR_WIDTH);
        int glassHeight = p.i(P_MONITOR_HEIGHT);
        if (span <= gapWidth || (span - gapWidth) % SLOPES != 0) {
            throw ctx.fail(node, IssueCode.E_PARAM_RANGE, P_MONITOR_WIDTH,
                    "屋根の幅(" + span + ")と越屋根の幅(" + gapWidth + ")の差が、正の偶数になるようにしてください");
        }
        int layers = (span - gapWidth) / SLOPES;
        String stairsId = ctx.palette().stairs(material, node);
        BlockSpec glass = ctx.plainBlock(ROLE_GLASS, node);
        BlockSpec cap = BlockForms.slab(ctx.palette().slab(material, node), UPSIDE_DOWN);
        BlockSpec wall = p.b(P_GABLE_FILL) ? ctx.plainBlock(ROLE_WALL, node) : null;
        slopes(ctx, node, f, base, layers, stairsId);
        int aM0 = f.a0() + layers;
        int aM1 = f.a1() - layers;
        int sill = base + layers;
        for (int dr = 0; dr < glassHeight; dr++) {
            row(ctx, node, f, aM0, sill + dr, glass);
            if (aM1 != aM0) {
                row(ctx, node, f, aM1, sill + dr, glass);
            }
        }
        for (int a = aM0; a <= aM1; a++) {
            row(ctx, node, f, a, sill + glassHeight, cap);
        }
        if (wall != null) {
            fillEnds(ctx, node, st, f, base, a -> Math.min(Math.min(a - f.a0(), f.a1() - a), layers), wall);
            for (int b : endFaces(footprint(st, f))) {
                for (int a = aM0 + 1; a < aM1; a++) {
                    for (int dr = 0; dr < glassHeight; dr++) {
                        ctx.emitAbs(node, f.pos(a, sill + dr, b), glass);
                    }
                }
            }
        }
    }
}
