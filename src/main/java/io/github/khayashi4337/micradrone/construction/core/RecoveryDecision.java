package io.github.khayashi4337.micradrone.construction.core;

/**
 * What {@link RecoveryPlanner} decided for a saved job: the job to keep (possibly re-paused), the load it came from,
 * and whether its part of the write-ahead log still has to be folded in ({@code fromLog}: every readable job but a
 * pending approval — finished jobs included, because the log may hold a write the world lost or a settle its ledger
 * still owes).
 */
public record RecoveryDecision(ConstructionJob job, JobLoad load, boolean fromLog) {
}
