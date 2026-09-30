package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.MaterialFamilies;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** The fallback allow list of building materials, built from the default palette, the families and common vanilla groups. */
final class BuiltinAllowList {
    private static final String NAMESPACE = "minecraft:";
    private static final String STRIPPED_PREFIX = "stripped_";
    private static final String NAME_SEPARATOR = "_";

    private static final List<String> COLORS = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
            "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");
    /** The blocks that come in every dye color, as {@code <color>_<kind>}. */
    private static final List<String> COLORED_KINDS = List.of("concrete", "terracotta", "wool", "stained_glass",
            "stained_glass_pane", "glazed_terracotta");
    private static final List<String> WOODS = List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove",
            "cherry", "bamboo", "crimson", "warped");
    /** The blocks that every wood type has, as {@code <wood>_<kind>}. */
    private static final List<String> WOOD_KINDS = List.of("planks", "fence", "fence_gate", "door", "trapdoor", "wall_sign",
            "stairs", "slab");
    private static final List<String> LOG_WOODS = List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry");
    private static final List<String> LOG_KINDS = List.of("log", "wood");
    /** Woods whose trunks are not logs: bamboo blocks and the nether stems. */
    private static final List<String> OTHER_TRUNKS = List.of("bamboo_block", "stripped_bamboo_block", "crimson_stem",
            "crimson_hyphae", "warped_stem", "warped_hyphae");
    private static final List<String> FLOWERS = List.of("poppy", "dandelion", "blue_orchid", "allium", "azure_bluet",
            "red_tulip", "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy", "cornflower", "lily_of_the_valley",
            "fern", "azalea", "flowering_azalea");
    private static final List<String> OTHERS = List.of("glass", "glass_pane", "iron_bars", "iron_block", "iron_door",
            "iron_trapdoor", "terracotta", "hay_block", "dirt", "coarse_dirt", "gravel", "sand", "dirt_path", "barrel",
            "lantern", "torch", "ladder", "smooth_stone", "bricks", "cobblestone", "stone", "sandstone");

    private BuiltinAllowList() {
    }

    static Set<String> ids() {
        Set<String> out = new TreeSet<>(BuildingParts.DEFAULT_PALETTE.values());
        for (String full : MaterialFamilies.fullBlockIds()) {
            MaterialFamilies.Family f = MaterialFamilies.family(full).orElseThrow();
            out.add(f.full());
            if (f.stairs() != null) {
                out.add(f.stairs());
            }
            out.add(f.slab());
        }
        addCombinations(out, "", COLORS, COLORED_KINDS);
        addCombinations(out, "", WOODS, WOOD_KINDS);
        addCombinations(out, "", LOG_WOODS, LOG_KINDS);
        addCombinations(out, STRIPPED_PREFIX, LOG_WOODS, LOG_KINDS);
        addPlain(out, OTHER_TRUNKS);
        addPlain(out, FLOWERS);
        addPlain(out, OTHERS);
        return out;
    }

    /** Adds {@code minecraft:<prefix><first>_<second>} for every pair. */
    private static void addCombinations(Set<String> out, String prefix, List<String> firsts, List<String> seconds) {
        for (String first : firsts) {
            for (String second : seconds) {
                out.add(NAMESPACE + prefix + first + NAME_SEPARATOR + second);
            }
        }
    }

    private static void addPlain(Set<String> out, List<String> names) {
        for (String name : names) {
            out.add(NAMESPACE + name);
        }
    }
}
