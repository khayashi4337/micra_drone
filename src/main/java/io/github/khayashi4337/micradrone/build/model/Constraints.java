package io.github.khayashi4337.micradrone.build.model;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Limits on an automatically routed connection. Null numbers mean "no limit". */
public record Constraints(Integer maxLength, Set<String> avoidNodeIds, Integer maxTurns, Set<Dir6> allowedEntryDirs) {
    public static final Constraints NONE = new Constraints(null, Set.of(), null, Set.of());

    public Constraints {
        avoidNodeIds = Collections.unmodifiableSortedSet(
                new TreeSet<>(Objects.requireNonNullElse(avoidNodeIds, Set.of())));
        allowedEntryDirs = Collections.unmodifiableSortedSet(
                new TreeSet<>(Objects.requireNonNullElse(allowedEntryDirs, Set.of())));
    }
}
