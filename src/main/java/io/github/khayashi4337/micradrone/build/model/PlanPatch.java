package io.github.khayashi4337.micradrone.build.model;

import java.util.List;
import java.util.Objects;

/** What the AI (or a script) produces: a list of operations against a specific base revision. */
public record PlanPatch(String patchId, int baseRevision, String stageId, List<PlanOp> ops) {
    public PlanPatch {
        stageId = Objects.requireNonNullElse(stageId, "");
        ops = List.copyOf(ops);
    }
}
