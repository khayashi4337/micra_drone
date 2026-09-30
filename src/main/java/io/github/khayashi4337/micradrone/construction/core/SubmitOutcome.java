package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.Issue;
import java.util.List;

/**
 * The latest outcome of a {@link PlanSubmission}, per owner. {@code state} is {@code WORKING} while a survey,
 * the compilation or the snapshot reads are still going on, {@code OFFERED} once an approval sits in the desk,
 * and {@code FAILED} when nothing will be offered. {@code etaTicks} is the human-facing estimate of the offer;
 * it is {@code 0} for the other states. Shared by the runtime, the commands and the devkit: the server is the
 * only place a submission can live, so the type is pure and has no adapter imports (D-16).
 */
public record SubmitOutcome(String state, PendingApproval pending, List<Issue> issues, ReplacementSummary replacements,
        long etaTicks) {
    public static final String WORKING = "WORKING";
    public static final String OFFERED = "OFFERED";
    public static final String FAILED = "FAILED";

    public SubmitOutcome {
        if (!state.equals(WORKING) && !state.equals(OFFERED) && !state.equals(FAILED)) {
            throw new IllegalArgumentException("state must be WORKING, OFFERED or FAILED: " + state);
        }
        if (state.equals(OFFERED) && pending == null) {
            throw new IllegalArgumentException("an OFFERED outcome must carry its PendingApproval");
        }
        issues = List.copyOf(issues);
    }

    /** A submission that is still being surveyed, compiled or read. */
    public static SubmitOutcome working() {
        return new SubmitOutcome(WORKING, null, List.of(), null, 0);
    }

    /** A submission that will never produce an approval. {@code issues} explains why. */
    public static SubmitOutcome failed(List<Issue> issues) {
        return new SubmitOutcome(FAILED, null, issues, null, 0);
    }

    /** A submission that produced an approval offer. {@code etaTicks} is the displayed ETA. */
    public static SubmitOutcome offered(PendingApproval pending, List<Issue> issues, ReplacementSummary replacements,
            long etaTicks) {
        return new SubmitOutcome(OFFERED, pending, issues, replacements, etaTicks);
    }
}
