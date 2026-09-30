package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TerrainSummary;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.Issue;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The server's own rebuild of a submission: the manifest it computed (null when an ERROR refused the compile, and
 * then {@code operatingBox} is null too), every issue found, the terrain summary, the node id to part-type map and
 * the box a claim would protect.
 */
public record CompiledPlan(PlacementManifest manifest, List<Issue> issues, TerrainSummary terrain,
                           Map<String, String> nodeTypes, Box operatingBox) {
    public CompiledPlan {
        issues = List.copyOf(issues);
        Objects.requireNonNull(terrain, "terrain");
        nodeTypes = Map.copyOf(nodeTypes);
    }
}
