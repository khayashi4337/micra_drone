package io.github.khayashi4337.micradrone.build.plan;

import io.github.khayashi4337.micradrone.build.model.Issue;
import java.util.List;

/** What expanding a plan produced: the expanded plan, or null when at least one issue is an ERROR. */
public record ExpandResult(ExpandedPlan plan, List<Issue> issues) {
    public ExpandResult {
        issues = List.copyOf(issues);
    }
}
