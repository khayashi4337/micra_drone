package io.github.khayashi4337.micradrone.build.plan;

import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import java.util.List;

/** What applying a patch produced: the new plan, or null when at least one issue is an ERROR. */
public record PatchResult(SemanticPlan plan, List<Issue> issues) {
    public PatchResult {
        issues = List.copyOf(issues);
    }

    /** True when the patch was applied; an ERROR anywhere makes the whole patch refused (plan is null). */
    public boolean ok() {
        return plan != null;
    }
}
