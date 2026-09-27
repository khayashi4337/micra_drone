package io.github.khayashi4337.micradrone.build.model;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Unmodifiable copies in natural order (dictionary order for strings). Records that hold sets or maps get a stable
 * iteration order and a defensive copy this way, so neither the record's state nor its hash depends on how the
 * caller built the collection. A null input is an empty copy, matching the null-forgiving convention of the
 * records here.
 */
public final class SortedCopies {
    private SortedCopies() {
    }

    public static <T> SortedSet<T> set(Set<T> items) {
        return Collections.unmodifiableSortedSet(new TreeSet<>(items == null ? Set.of() : items));
    }

    public static <K, V> SortedMap<K, V> map(Map<K, V> entries) {
        return Collections.unmodifiableSortedMap(new TreeMap<>(entries == null ? Map.of() : entries));
    }
}
