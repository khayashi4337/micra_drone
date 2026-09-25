package io.github.khayashi4337.micradrone.build.compile;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which blocks a plan may place (D-22). The materials of the palette must be on the allow list; some blocks
 * are refused whatever the list says. The runtime replaces the built-in list with the datapack tag
 * micradrone:palette_allowed (P4); this class is the pure part of that check.
 */
public final class PlaceableBlockPolicy {
    public static final Set<String> ALWAYS_FORBIDDEN = Collections.unmodifiableSet(new TreeSet<>(Set.of(
            "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
            "minecraft:bedrock", "minecraft:spawner", "minecraft:trial_spawner", "minecraft:vault", "minecraft:barrier",
            "minecraft:structure_block", "minecraft:structure_void", "minecraft:jigsaw", "minecraft:light",
            "minecraft:end_portal", "minecraft:end_portal_frame", "minecraft:end_gateway", "minecraft:nether_portal",
            "minecraft:reinforced_deepslate")));

    private final Set<String> paletteAllowed;

    public PlaceableBlockPolicy(Set<String> paletteAllowed) {
        this.paletteAllowed = Collections.unmodifiableSet(new TreeSet<>(paletteAllowed));
    }

    public static PlaceableBlockPolicy builtin() {
        return new PlaceableBlockPolicy(BuiltinAllowList.ids());
    }

    public boolean isAlwaysForbidden(String blockId) {
        return ALWAYS_FORBIDDEN.contains(blockId);
    }

    public Set<String> allowed() {
        return paletteAllowed;
    }

    /** Empty when the block may be used as a material; otherwise why not (in Japanese, for the user). */
    public Optional<String> checkMaterial(String blockId) {
        if (isAlwaysForbidden(blockId)) {
            return Optional.of(blockId + "は、置いてはいけないブロックです");
        }
        if (!paletteAllowed.contains(blockId)) {
            return Optional.of(blockId + "は、素材として許可されていません(許可リストにありません)");
        }
        return Optional.empty();
    }
}
