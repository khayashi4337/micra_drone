package io.github.khayashi4337.micradrone.build.compile;

import java.util.Optional;
import java.util.Set;

/**
 * The runtime palette (D-22): the palette_allowed tag when the datapack defines it, even empty (an administrator may
 * forbid everything on purpose); the built-in table only when the tag is absent (it equals the shipped tag, see
 * BuildTagFilesTest, so an absent datapack never allows more).
 */
public final class PalettePolicy {
    private PalettePolicy() {
    }

    public static PlaceableBlockPolicy from(Optional<Set<String>> tagIds) {
        return tagIds.map(PlaceableBlockPolicy::new).orElseGet(PlaceableBlockPolicy::builtin);
    }
}
