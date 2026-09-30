package io.github.khayashi4337.micradrone.build.model;

import java.util.List;

/**
 * One local change the compiler made so that an opening stays usable — a window widened around a corner into a
 * corner window, or a door whose way in was dug through the neighbouring wall's inside. The opening counterpart of
 * {@code Adjustment} (the Zoning Fixer's record): the authoritative record, where {@code W-OPENING-ADJUSTED} is only
 * the projection shown to the user.
 * <p>
 * {@code openingId} is the opening the change was made for, {@code wallIds} the walls whose cells changed,
 * {@code rule} the rule that produced the change and {@code ruleVersion} its version, {@code cells} the changed cells
 * in construction order, {@code reason} the machine-readable cause, and {@code note} the plain explanation for the
 * child watching the build (e.g. "窓のすぐ後ろに角の壁があったので、東側の2列もガラスにして角窓にしました。").
 */
public record OpeningAdjustment(String openingId, List<String> wallIds, String rule, int ruleVersion,
                                List<ChangedCell> cells, String reason, String note) {

    /** One changed cell: what claimed it before (the wall's claim as {@code ownerId:blockId}) and what it became. */
    public record ChangedCell(LocalPos pos, String before, String after) {
    }

    public OpeningAdjustment {
        wallIds = List.copyOf(wallIds);
        cells = List.copyOf(cells);
    }
}
