package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.plan.TemplateBundle;
import java.util.Objects;

/**
 * What the client may submit (04 F-3): the plan, the templates it refers to, the job kind and, for MODIFY, the
 * claim it works on. There is deliberately no slot for the client's expansion or placement list: the server
 * rebuilds those itself (D-3). {@code claimId} is null for BUILD.
 */
public record PlanSubmission(SemanticPlan plan, TemplateBundle templates, JobKind kind, String parentJobId,
                             String claimId) {
    public PlanSubmission {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(templates, "templates");
        Objects.requireNonNull(kind, "kind");
    }
}
