package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.Issue;
import java.util.List;

/** What compiling produced: the manifest, or null when at least one issue is an ERROR. */
public record CompileResult(PlacementManifest manifest, List<Issue> issues) {
    public CompileResult {
        issues = List.copyOf(issues);
    }
}
