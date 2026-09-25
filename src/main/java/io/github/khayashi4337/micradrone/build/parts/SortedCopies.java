package io.github.khayashi4337.micradrone.build.parts;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Unmodifiable copies in dictionary order. The part records hold sets and maps that are hashed into the registry
 * version, so their iteration order must not depend on how the caller built them. A null input is an empty copy.
 */
final class SortedCopies {
    private SortedCopies() {
    }

    static Set<String> set(Set<String> items) {
        return Collections.unmodifiableSortedSet(new TreeSet<>(Objects.requireNonNullElse(items, Set.of())));
    }

    static Map<String, String> map(Map<String, String> entries) {
        return Collections.unmodifiableSortedMap(new TreeMap<>(Objects.requireNonNullElse(entries, Map.of())));
    }
}
