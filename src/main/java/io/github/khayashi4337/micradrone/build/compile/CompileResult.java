package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.OpeningAdjustment;
import java.util.List;

/**
 * What compiling produced: the manifest, or null when at least one issue is an ERROR; the issues; and the local
 * corrections the opening resolver made (the {@link OpeningAdjustment} records {@code W-OPENING-ADJUSTED} announces).
 */
public record CompileResult(PlacementManifest manifest, List<Issue> issues, List<OpeningAdjustment> adjustments) {
    public CompileResult {
        issues = List.copyOf(issues);
        adjustments = List.copyOf(adjustments);
    }

    public CompileResult(PlacementManifest manifest, List<Issue> issues) {
        this(manifest, issues, List.of());
    }
}
