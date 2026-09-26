package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.parts.Params;
import java.util.Map;
import java.util.TreeMap;

/**
 * A wall sign on a wall face, one cell wide and one row high, holding up to four lines of text. OUTER puts it on
 * the first cell outside the wall facing outward; INNER puts it on the first cell inside facing inward. The text
 * is split on "|" into lines; an empty line stays empty and writes no {@code lineN} key.
 */
final class SignGen implements PartGenerator {
    private static final String P_TEXT = "text";
    private static final String P_MATERIAL = "material";
    /** The separator the text is split on (a regex, so the literal is escaped). */
    private static final String LINE_SEPARATOR = "\\|";
    /** The block entity key of line {@code n} is {@code line<n>}. */
    private static final String LINE_KEY_PREFIX = "line";
    /** What a sign block holds: four lines of at most fifteen characters each. */
    private static final int MAX_LINES = 4;
    private static final int MAX_LINE_CHARS = 15;
    /** {@code layer} -1 of a wall is the first cell outside its outermost layer; {@code thickness} is just inside. */
    private static final int OUTSIDE_LAYER = -1;

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        WallInfo wall = ctx.wallOfAnchor(node); // refuses anything but an OnSurface anchor on a wall, so the cast holds
        Anchor.OnSurface a = (Anchor.OnSurface) node.anchor();
        String[] lines = p.s(P_TEXT).split(LINE_SEPARATOR, -1);
        if (lines.length > MAX_LINES) {
            throw ctx.fail(node, IssueCode.E_PARAM_RANGE, P_TEXT,
                    "看板は" + MAX_LINES + "行までです(" + lines.length + "行あります)");
        }
        Map<String, String> config = new TreeMap<>();
        for (int k = 0; k < lines.length; k++) {
            if (lines[k].codePointCount(0, lines[k].length()) > MAX_LINE_CHARS) {
                throw ctx.fail(node, IssueCode.E_PARAM_RANGE, P_TEXT,
                        (k + 1) + "行目が" + MAX_LINE_CHARS + "字を超えています");
            }
            if (!lines[k].isEmpty()) {
                config.put(LINE_KEY_PREFIX + (k + 1), lines[k]);
            }
        }
        boolean outer = a.side() == Side.OUTER;
        Facing facing = outer ? wall.outward() : wall.outward().opposite();
        int layer = outer ? OUTSIDE_LAYER : wall.thickness();
        // The only block the part places, asked for before it goes down: a refused material leaves no cell behind.
        String id = ctx.palette().full(p.s(P_MATERIAL), node);
        ctx.emitAbs(node, wall.cell(a.u(), layer, a.v()), BlockForms.wallSign(id, facing), null, null, config);
    }
}
