package io.github.khayashi4337.micradrone.build.compile;

import java.util.List;
import java.util.Objects;

/**
 * The difference between two manifests of one MODIFY job: removals ordered top-down, additions and
 * changes in the new manifest's index order, and how many placements did not change.
 */
public record ManifestDiff(String fromHash, String toHash, List<RemovalEntry> removals, List<Placement> additions,
                           List<PlacementChange> changes, int unchanged) {
    public ManifestDiff {
        Objects.requireNonNull(fromHash, "fromHash");
        Objects.requireNonNull(toHash, "toHash");
        removals = List.copyOf(Objects.requireNonNull(removals, "removals"));
        additions = List.copyOf(Objects.requireNonNull(additions, "additions"));
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
    }
}
