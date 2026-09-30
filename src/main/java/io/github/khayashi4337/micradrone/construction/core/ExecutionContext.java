package io.github.khayashi4337.micradrone.construction.core;

/** Everything one executor call works on. {@code wal} is shared by all jobs of the server (runs are numbered globally). */
public record ExecutionContext(ConstructionJob job, JobProgram program, WorldPort world, MaterialPort materials, Journal journal,
                               LedgerBook ledgers, PlacedRegistry registry, JobOutcome outcome, boolean skipSiteChanges,
                               WriteAheadLog wal) {
}
