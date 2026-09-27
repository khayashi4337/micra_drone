package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.Side;
import java.util.ArrayList;
import java.util.List;

/**
 * Where an opening (a door or a window) sits on a wall: which wall, the first cell along it, the first row, its size,
 * and the layer of the wall that its blocks go in. The opening takes out every layer of the wall over its size, but only
 * {@code layer} gets anything back: an OUTER opening sits in the outermost layer, an INNER one in the innermost.
 */
record OpeningSpot(WallInfo wall, int i, int row, boolean outer, int layer, int width, int height) {
    private static final int OUTERMOST_LAYER = 0;
    /** The Issue key of an opening that leaves the wall: none, like the carve's own E-OPENING-NO-WALL. */
    private static final String NO_KEY = "";

    /** Reads the spot off the node's OnSurface anchor; an anchor that is not on a valid wall face ends the part with an issue. */
    static OpeningSpot of(GenContext ctx, PlanNode node, int width, int height) {
        WallInfo wall = ctx.wallOfAnchor(node); // refuses anything but an OnSurface anchor on a wall, so the cast holds
        Anchor.OnSurface a = (Anchor.OnSurface) node.anchor();
        boolean outer = a.side() == Side.OUTER;
        return new OpeningSpot(wall, a.u(), a.v(), outer, outer ? OUTERMOST_LAYER : wall.thickness() - 1, width, height);
    }

    /**
     * The spot of an opening, with its cells already taken out of the wall. The anchor only shows that the opening's
     * first cell is on the wall, so the whole extent is checked against the wall's own length and height first
     * (E-OPENING-NO-WALL, nothing changed): {@link GenContext#carve} only tells this building's walls from other cells,
     * so it would accept a cell of the next wall segment on the same side, or of the storey above. What is left for the
     * carve to refuse is a cell inside the wall's extent that is no longer a wall cell, and a cell that another opening took.
     */
    static OpeningSpot carved(GenContext ctx, PlanNode node, int width, int height) {
        OpeningSpot spot = of(ctx, node, width, height);
        spot.requireInsideWall(ctx, node);
        ctx.carve(node, spot.wall(), spot.cells());
        return spot;
    }

    /** Refuses an opening that would leave the wall's own extent, with the same issue (no key) as the carve's. */
    private void requireInsideWall(GenContext ctx, PlanNode node) {
        if (!wall.fitsAlong(i, width) || !wall.fitsUp(row, height)) {
            throw ctx.fail(node, IssueCode.E_OPENING_NO_WALL, NO_KEY, node.id() + "の開口部(u=" + i + "から幅" + width + "、v=" + row
                    + "から高さ" + height + ")が、壁(" + wall.id() + ")の外にはみ出しています(壁は長さ" + wall.length() + "、高さ"
                    + wall.height() + ")");
        }
    }

    /** Every cell the opening replaces: all layers of the wall over the opening's width and height. */
    List<LocalPos> cells() {
        List<LocalPos> out = new ArrayList<>();
        for (int di = 0; di < width; di++) {
            for (int dr = 0; dr < height; dr++) {
                for (int through = 0; through < wall.thickness(); through++) {
                    out.add(wall.cell(i + di, through, row + dr));
                }
            }
        }
        return out;
    }

    /** The position, in the layer the blocks go in, of the cell {@code di} along the opening and {@code dRow} up from its bottom. */
    LocalPos at(int di, int dRow) {
        return wall.cell(i + di, layer, row + dRow);
    }
}
