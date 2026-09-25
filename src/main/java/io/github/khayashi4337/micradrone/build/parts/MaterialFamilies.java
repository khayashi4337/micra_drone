package io.github.khayashi4337.micradrone.build.parts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Vanilla block families that have both stairs and slabs, keyed by the full block. Roofs, ramps and trims need the
 * stairs or slab form of a material; a material outside this table (e.g. terracotta, which has no stairs in
 * vanilla) can only be used as a full block. The names were checked against the 1.21.1 client jar's blockstates.
 */
public final class MaterialFamilies {
    public record Family(String full, String stairs, String slab) {
    }

    private static final String NAMESPACE_PREFIX = "minecraft:";
    private static final String PLANKS_SUFFIX = "_planks";
    private static final String STAIRS_SUFFIX = "_stairs";
    private static final String SLAB_SUFFIX = "_slab";
    private static final String PLURAL_SUFFIX = "s";

    /** Wood types: the full block is {@code <wood>_planks}. */
    private static final List<String> WOODS = List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
            "mangrove", "cherry", "bamboo", "crimson", "warped");

    /** Families whose stairs and slab are named after the full block itself. */
    private static final List<String> SAME_NAME = List.of("stone", "cobblestone", "mossy_cobblestone", "sandstone",
            "smooth_sandstone", "red_sandstone", "smooth_red_sandstone", "smooth_quartz", "prismarine", "dark_prismarine",
            "andesite", "polished_andesite", "diorite", "polished_diorite", "granite", "polished_granite",
            "cobbled_deepslate", "polished_deepslate", "blackstone", "polished_blackstone", "cut_copper",
            "exposed_cut_copper", "weathered_cut_copper", "oxidized_cut_copper", "tuff", "polished_tuff");

    /** Families whose block name is plural (<name>s) while the stairs and slab name is singular. */
    private static final List<String> PLURAL_BLOCK = List.of("stone_brick", "mossy_stone_brick", "brick", "nether_brick",
            "red_nether_brick", "prismarine_brick", "deepslate_brick", "deepslate_tile", "polished_blackstone_brick",
            "end_stone_brick", "mud_brick", "tuff_brick");

    private static final TreeMap<String, Family> TABLE = new TreeMap<>();

    static {
        for (String wood : WOODS) {
            addWithStem(wood + PLANKS_SUFFIX, wood);
        }
        for (String name : SAME_NAME) {
            addWithStem(name, name);
        }
        for (String name : PLURAL_BLOCK) {
            addWithStem(name + PLURAL_SUFFIX, name);
        }
        addWithStem("quartz_block", "quartz");
        addWithStem("purpur_block", "purpur");
        add("smooth_stone", null, "smooth_stone" + SLAB_SUFFIX);
    }

    private MaterialFamilies() {
    }

    /** Adds a family whose stairs and slab are {@code <stem>_stairs} and {@code <stem>_slab}. */
    private static void addWithStem(String full, String stem) {
        add(full, stem + STAIRS_SUFFIX, stem + SLAB_SUFFIX);
    }

    private static void add(String full, String stairs, String slab) {
        String id = NAMESPACE_PREFIX + full;
        TABLE.put(id, new Family(id, stairs == null ? null : NAMESPACE_PREFIX + stairs, NAMESPACE_PREFIX + slab));
    }

    public static Optional<Family> family(String fullBlockId) {
        return Optional.ofNullable(TABLE.get(fullBlockId));
    }

    public static List<String> fullBlockIds() {
        return Collections.unmodifiableList(new ArrayList<>(TABLE.keySet()));
    }
}
