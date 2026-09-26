package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;

/** A window in a wall: one pane, a wide window of three, or an arch window with stairs at the top corners. */
final class WindowGen implements PartGenerator {
    private static final String P_LATTICE = "lattice";
    private static final String KIND_PANE = "pane";
    private static final String KIND_ARCH = "arch";
    /** The palette role of the arch's corner stairs and of a lattice's mullion. */
    private static final String ROLE_TRIM = "trim";
    private static final int PANE_WIDTH = 1;
    private static final int PANE_HEIGHT = 2;
    /** Both the wide and the arch window are three wide. */
    private static final int WIDE_WIDTH = 3;
    private static final int ARCH_HEIGHT = 3;
    /** The arch window is glazed across its full width in the two lower rows; the top row is the arch. */
    private static final int ARCH_FULL_ROWS = 2;
    // The columns of a three-wide window. A one-wide pane has no lattice, so its only column is never a mullion.
    private static final int FIRST_COLUMN = 0;
    private static final int MIDDLE_COLUMN = WIDE_WIDTH / 2;
    private static final int LAST_COLUMN = WIDE_WIDTH - 1;

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        String kind = p.s(OpeningSpot.P_KIND);
        boolean arch = kind.equals(KIND_ARCH);
        boolean lattice = p.b(P_LATTICE);
        int width = kind.equals(KIND_PANE) ? PANE_WIDTH : WIDE_WIDTH;
        int height = arch ? ARCH_HEIGHT : PANE_HEIGHT;
        if (lattice && width == PANE_WIDTH) {
            throw ctx.fail(node, IssueCode.E_PARAM_RANGE, P_LATTICE, "格子(lattice)は、幅のある窓(wide・arch)だけで使えます");
        }
        OpeningSpot spot = OpeningSpot.carved(ctx, node, width, height);
        // Every block that will be placed is asked of the palette before the first one goes down (a refused material
        // leaves no cell behind), and only those that will be placed: the trim is asked for by a lattice or an arch only.
        BlockSpec glass = BlockForms.plain(ctx.palette().full(p.s(OpeningSpot.P_MATERIAL), node));
        // the block of every cell of the middle column: the trim with a lattice, else the glass like the rest
        BlockSpec middleColumnBlock = lattice ? BlockForms.plain(ctx.palette().full(ROLE_TRIM, node)) : glass;
        String stairs = arch ? ctx.palette().stairs(ROLE_TRIM, node) : null;
        int fullRows = arch ? ARCH_FULL_ROWS : height;
        for (int di = 0; di < width; di++) {
            for (int dr = 0; dr < fullRows; dr++) {
                ctx.emitAbs(node, spot.at(di, dr), di == MIDDLE_COLUMN ? middleColumnBlock : glass);
            }
        }
        if (arch) {
            int top = ARCH_FULL_ROWS;
            // the middle column runs up through the top row too, to the crown of the arch
            ctx.emitAbs(node, spot.at(MIDDLE_COLUMN, top), middleColumnBlock);
            // the corner stairs are upside down with their backs to the outer ends of the arch
            ctx.emitAbs(node, spot.at(FIRST_COLUMN, top), BlockForms.stairs(stairs, spot.wall().along().opposite(), true));
            ctx.emitAbs(node, spot.at(LAST_COLUMN, top), BlockForms.stairs(stairs, spot.wall().along(), true));
        }
    }
}
