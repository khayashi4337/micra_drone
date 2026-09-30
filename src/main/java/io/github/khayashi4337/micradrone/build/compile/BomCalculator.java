package io.github.khayashi4337.micradrone.build.compile;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Counts the items needed for a manifest (item id to count). Mostly one item per block; which item a block
 * costs is {@link BlockToItem}'s table (design 04, F-7).
 */
public final class BomCalculator {
    private BomCalculator() {
    }

    public static Map<String, Integer> bom(List<Placement> placements) {
        TreeMap<String, Integer> out = new TreeMap<>();
        for (Placement p : placements) {
            BlockToItem.cost(p.block()).ifPresent(c -> out.merge(c.itemId(), c.count(), Integer::sum));
        }
        return Collections.unmodifiableSortedMap(out);
    }
}
