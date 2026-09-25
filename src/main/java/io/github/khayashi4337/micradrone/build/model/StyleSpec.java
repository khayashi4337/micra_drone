package io.github.khayashi4337.micradrone.build.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Role name to material (a block id), plus mood tags. Materials must pass the block policy (D-22) at compile time. */
public record StyleSpec(Map<String, String> palette, Set<String> moodTags) {
    public static final StyleSpec EMPTY = new StyleSpec(Map.of(), Set.of());

    public StyleSpec {
        palette = Collections.unmodifiableSortedMap(new TreeMap<>(Objects.requireNonNullElse(palette, Map.of())));
        moodTags = Collections.unmodifiableSortedSet(new TreeSet<>(Objects.requireNonNullElse(moodTags, Set.of())));
    }
}
