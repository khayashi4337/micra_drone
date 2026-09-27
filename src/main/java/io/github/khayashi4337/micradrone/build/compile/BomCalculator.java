package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.compile.gen.BlockForms;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Counts the items needed for a manifest (item id to count). Mostly one item per block; the exceptions are
 * listed here. Blocks with no item of their own are counted under the part that creates them (design 04, F-7).
 */
public final class BomCalculator {
    private static final Map<String, String> ITEM_OF_BLOCK = Map.of("create:belt", "create:belt_connector");
    /** A double slab is two slab items in one block. */
    private static final int ITEMS_PER_DOUBLE_SLAB = 2;

    private BomCalculator() {
    }

    public static Map<String, Integer> bom(List<Placement> placements) {
        TreeMap<String, Integer> out = new TreeMap<>();
        for (Placement p : placements) {
            BlockSpec b = p.block();
            String id = b.blockId();
            int count = 1;
            if (b.isAir()) {
                continue;
            }
            if (BlockForms.HALF_UPPER.equals(b.get(BlockForms.PROP_HALF)) && id.endsWith(BlockForms.DOOR_SUFFIX)) {
                continue; // a door is one item for both halves
            }
            if (BlockForms.TYPE_DOUBLE.equals(b.get(BlockForms.PROP_TYPE)) && id.endsWith(BlockForms.SLAB_SUFFIX)) {
                count = ITEMS_PER_DOUBLE_SLAB;
            }
            String item = ITEM_OF_BLOCK.getOrDefault(id, id);
            if (item.endsWith(BlockForms.WALL_SIGN_SUFFIX)) {
                item = item.substring(0, item.length() - BlockForms.WALL_SIGN_SUFFIX.length()) + BlockForms.SIGN_SUFFIX;
            }
            out.merge(item, count, Integer::sum);
        }
        return Collections.unmodifiableSortedMap(out);
    }
}
