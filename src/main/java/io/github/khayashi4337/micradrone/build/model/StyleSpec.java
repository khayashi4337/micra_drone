package io.github.khayashi4337.micradrone.build.model;

import java.util.Map;
import java.util.Set;

/** Role name to material (a block id), plus mood tags. Materials must pass the block policy (D-22) at compile time. */
public record StyleSpec(Map<String, String> palette, Set<String> moodTags) {
    public static final StyleSpec EMPTY = new StyleSpec(Map.of(), Set.of());

    public StyleSpec {
        palette = SortedCopies.map(palette);
        moodTags = SortedCopies.set(moodTags);
    }
}
