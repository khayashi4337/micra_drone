package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.Issue;
import java.util.List;

/** The server's answer to an approval. A rejection names the check that failed and the issues that block. */
public sealed interface ApprovalDecision {
    record Approved(ConstructionJob job, Candidate candidate) implements ApprovalDecision {
    }

    record Rejected(ApprovalRejection reason, List<Issue> blocking) implements ApprovalDecision {
        public Rejected {
            blocking = List.copyOf(blocking);
        }
    }
}
