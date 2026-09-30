package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.compile.gen.BlockForms;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Which item a placed block costs (04 F-7, the BlockToItem table) and which item cutting natural ground gives the owner.
 * The rule: a block costs exactly what breaking it gives back (its loot table without silk touch), so a rollback
 * returns what a build took. Mostly the block's own item; the exceptions are listed here: blocks with no item of their
 * own (IMPLICIT parts such as Create's powered shaft and belt, wall-mounted forms, redstone wire) cost the item they
 * drop, and the second block of a two-block piece (a door's upper half, a tall flower's top, a bed's head) costs
 * nothing. Whether an item exists in the game is the adapter's check (ItemCatalog).
 */
public final class BlockToItem {
    /** Blocks whose drop is another item (Create's loot tables: data/create/loot_table/blocks/{belt,powered_shaft}.json). */
    private static final Map<String, String> ITEM_OF_BLOCK = Map.of(
            "create:belt", "create:belt_connector",
            "create:powered_shaft", "create:shaft",
            "minecraft:redstone_wire", "minecraft:redstone",
            "minecraft:wall_torch", "minecraft:torch",
            "minecraft:soul_wall_torch", "minecraft:soul_torch",
            "minecraft:redstone_wall_torch", "minecraft:redstone_torch");
    /** Wall-mounted forms that drop their standing item: {@code *_wall_banner} gives {@code *_banner}, and so on. */
    private static final Map<String, String> WALL_FORM_SUFFIXES = Map.of(
            "_wall_hanging_sign", "_hanging_sign",
            "_wall_banner", "_banner",
            "_wall_head", "_head",
            "_wall_skull", "_skull");
    /** Two-block plants whose upper half drops nothing (the lower half drops the item). */
    private static final java.util.Set<String> TALL_PLANTS = java.util.Set.of("minecraft:sunflower", "minecraft:lilac",
            "minecraft:rose_bush", "minecraft:peony", "minecraft:tall_grass", "minecraft:large_fern",
            "minecraft:pitcher_plant");
    private static final String PROP_PART = "part";
    private static final String BED_HEAD = "head";
    private static final String BED_SUFFIX = "_bed";
    /** A double slab is two slab items in one block. */
    private static final int ITEMS_PER_DOUBLE_SLAB = 2;
    /** What mining the ground gives (vanilla loot without silk touch), so cutting neither makes nor loses resources. */
    private static final Map<String, String> CUT_YIELD = Map.of(
            "minecraft:grass_block", "minecraft:dirt",
            "minecraft:dirt_path", "minecraft:dirt",
            "minecraft:farmland", "minecraft:dirt",
            "minecraft:mycelium", "minecraft:dirt",
            "minecraft:podzol", "minecraft:dirt",
            "minecraft:stone", "minecraft:cobblestone",
            "minecraft:deepslate", "minecraft:cobbled_deepslate");

    private BlockToItem() {
    }

    public static Optional<ItemCount> cost(BlockSpec block) {
        String id = block.blockId();
        if (block.isAir()) {
            return Optional.empty();
        }
        boolean upper = BlockForms.HALF_UPPER.equals(block.get(BlockForms.PROP_HALF));
        if (upper && (id.endsWith(BlockForms.DOOR_SUFFIX) || TALL_PLANTS.contains(id))) {
            return Optional.empty();
        }
        if (id.endsWith(BED_SUFFIX) && BED_HEAD.equals(block.get(PROP_PART))) {
            return Optional.empty();
        }
        int count = BlockForms.TYPE_DOUBLE.equals(block.get(BlockForms.PROP_TYPE)) && id.endsWith(BlockForms.SLAB_SUFFIX)
                ? ITEMS_PER_DOUBLE_SLAB : 1;
        String item = ITEM_OF_BLOCK.getOrDefault(id, id);
        if (item.endsWith(BlockForms.WALL_SIGN_SUFFIX)) {
            item = item.substring(0, item.length() - BlockForms.WALL_SIGN_SUFFIX.length()) + BlockForms.SIGN_SUFFIX;
        }
        for (Map.Entry<String, String> wall : WALL_FORM_SUFFIXES.entrySet()) {
            if (item.endsWith(wall.getKey())) {
                item = item.substring(0, item.length() - wall.getKey().length()) + wall.getValue();
            }
        }
        return Optional.of(new ItemCount(item, count));
    }

    public static Optional<ItemCount> cutYield(BlockSpec block) {
        if (block.isAir()) {
            return Optional.empty();
        }
        return Optional.of(new ItemCount(CUT_YIELD.getOrDefault(block.blockId(), block.blockId()), 1));
    }

    public static List<ItemCount> merge(List<ItemCount> items) {
        TreeMap<String, Long> sum = new TreeMap<>();
        ItemCount.addAll(sum, items);
        List<ItemCount> out = new ArrayList<>();
        sum.forEach((id, n) -> out.add(new ItemCount(id, n.intValue())));
        return out;
    }
}
