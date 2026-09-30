package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.Issue;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of {@link TerrainPrep#apply}: the rewritten manifest (null when an issue refused the run) and the
 * summary of what the terrain work would do.
 */
public record TerrainResult(PlacementManifest manifest, TerrainSummary summary, List<Issue> issues) {
    public TerrainResult {
        Objects.requireNonNull(summary, "summary");
        issues = List.copyOf(issues);
    }
}
