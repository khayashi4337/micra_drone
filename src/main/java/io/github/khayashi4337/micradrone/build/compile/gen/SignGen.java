package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.Params;
import io.github.khayashi4337.micradrone.build.parts.PartParams;
import java.util.Map;
import java.util.TreeMap;

/**
 * A wall sign on a wall face, one cell wide and one row high, holding up to four lines of text. OUTER puts it on
 * the first cell outside the wall facing outward; INNER puts it on the first cell inside facing inward. The text
 * is split on "|" into lines; an empty line stays empty and writes no {@code lineN} key.
 */
final class SignGen implements PartGenerator {
    /** The separator the text is split on (a regex, so the literal is escaped). */
    private static final String LINE_SEPARATOR = "\\|";
    /** The block entity key of line {@code n} is {@code line<n>}. */
    private static final String LINE_KEY_PREFIX = "line";

    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        WallInfo wall = ctx.wallOfAnchor(node); // refuses anything but an OnSurface anchor on a wall, so the cast holds
        Anchor.OnSurface a = (Anchor.OnSurface) node.anchor();
        String[] lines = p.s(PartParams.TEXT).split(LINE_SEPARATOR, -1);
        if (lines.length > BuildingParts.SIGN_MAX_LINES) {
            throw ctx.fail(node, IssueCode.E_PARAM_RANGE, PartParams.TEXT,
                    "看板は" + BuildingParts.SIGN_MAX_LINES + "行までです(" + lines.length + "行あります)");
        }
        Map<String, String> config = new TreeMap<>();
        for (int k = 0; k < lines.length; k++) {
            // Per line the limit counts code points — what a player reads as characters. The registry's total
            // bound (ParamValidator) counts UTF-16 units instead; the two differ only for astral characters.
            if (lines[k].codePointCount(0, lines[k].length()) > BuildingParts.SIGN_MAX_LINE_CHARS) {
                throw ctx.fail(node, IssueCode.E_PARAM_RANGE, PartParams.TEXT,
                        (k + 1) + "行目が" + BuildingParts.SIGN_MAX_LINE_CHARS + "字を超えています");
            }
            if (!lines[k].isEmpty()) {
                config.put(LINE_KEY_PREFIX + (k + 1), lines[k]);
            }
        }
        boolean outer = a.side() == Side.OUTER;
        Facing facing = outer ? wall.outward() : wall.outward().opposite();
        int layer = wall.faceLayer(outer);
        // The only block the part places, asked for before it goes down: a refused material leaves no cell behind.
        String id = ctx.palette().full(p.s(PartParams.MATERIAL), node);
        ctx.emitAbs(node, wall.cell(a.u(), layer, a.v()), BlockForms.wallSign(id, facing), null, null, config);
    }
}
