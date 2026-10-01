package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.verify.Deviation;
import io.github.khayashi4337.micradrone.build.verify.SnapshotCollector;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One job's runtime state. The job record itself (small) is saved as job.bin; the manifest, journal, ledger, outcome
 * and program are its separate files (04 F-2). The collector and repair queue are rebuilt after a restart.
 */
public final class JobRecord {
    private ConstructionJob job;
    private final PlacementManifest manifest;
    private final Map<String, String> nodeTypes;
    private final JobProgram program;
    private final Journal journal;
    /** The newest write-ahead-log run this job made (its end waits for a durable point covering it). */
    long lastRun;
    boolean awaitingDurable;
    /** Recovered while a repair round was under way: the next repair plan stays in that round. */
    boolean recoveredRound;
    private Journal beforeJournal;
    private final JobOutcome outcome;
    private final Box operatingBox;
    boolean skipSiteChanges;
    RepairQueue repair;
    SnapshotCollector collector;
    final List<Deviation> placementDeviations = new ArrayList<>();
    final List<Integer> restoreFailures = new ArrayList<>();
    final List<Deviation> remaining = new ArrayList<>();
    /** Far in the past, so a fresh job's drones may place on its first tick. */
    long lastDroneTick = Long.MIN_VALUE / 2;
    long admittedOrder;
    long pausedAtTick;
    /** Loaded with files older than the log: its log part must be folded in before the job may move again. */
    boolean recoveryPending;
    /** The log part after the last durable point that waits for the owner's answer (pending_log.bin), or empty. */
    List<WalEntry> pendingLog = List.of();
    /** Containers whose dropped contents a crash may have lost (shown by status --debug). */
    List<IntPos> possiblyLostDrops = List.of();
    /** Some of its files could not be read: the job never runs silently, the owner chooses repair or fail. */
    boolean broken;

    public JobRecord(ConstructionJob job, PlacementManifest manifest, Map<String, String> nodeTypes, JobProgram program,
                     Journal journal, JobOutcome outcome, Box operatingBox) {
        this.job = Objects.requireNonNull(job, "job");
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.nodeTypes = Map.copyOf(nodeTypes);
        this.program = Objects.requireNonNull(program, "program");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.operatingBox = Objects.requireNonNull(operatingBox, "operatingBox");
    }

    public ConstructionJob job() {
        return job;
    }

    void setJob(ConstructionJob next) {
        job = Objects.requireNonNull(next, "job");
    }

    public PlacementManifest manifest() {
        return manifest;
    }

    public Map<String, String> nodeTypes() {
        return nodeTypes;
    }

    public JobProgram program() {
        return program;
    }

    public Journal journal() {
        return journal;
    }

    /** Where the pre-build blocks are looked up: the job's own journal, or its parent's for a REPAIR job (Task 21). */
    public Journal beforeJournal() {
        return beforeJournal != null ? beforeJournal : journal;
    }

    void useBeforesFrom(Journal parent) {
        beforeJournal = parent;
    }

    public JobOutcome outcome() {
        return outcome;
    }

    public Box operatingBox() {
        return operatingBox;
    }

    public RepairQueue repair() {
        return repair;
    }

    public List<Deviation> remaining() {
        return List.copyOf(remaining);
    }

    public boolean skipSiteChanges() {
        return skipSiteChanges;
    }

    public long lastDroneTick() {
        return lastDroneTick;
    }

    public boolean recoveryPending() {
        return recoveryPending;
    }

    /** Marks a loaded job as needing its part of the write-ahead log folded in before it may move again. */
    public void requireRecovery() {
        recoveryPending = true;
    }

    public List<IntPos> possiblyLostDrops() {
        return possiblyLostDrops;
    }
}
