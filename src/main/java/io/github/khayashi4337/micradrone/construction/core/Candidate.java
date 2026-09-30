package io.github.khayashi4337.micradrone.construction.core;

/** Everything an approval needs together: the pending entry, the server's compile, the safety verdict, the submission. */
public record Candidate(PendingApproval approval, CompiledPlan compiled, SafetyReport safety, PlanSubmission submission) {
}
