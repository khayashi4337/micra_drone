package io.github.khayashi4337.micradrone.construction.core;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The small, saved state of one construction job (design 01, section 8). The large parts (manifest, journal, ledger)
 * live in separate files named here (04 F-2). The job id is used in file paths, so its form is checked (F-22).
 */
public record ConstructionJob(int schemaVersion, String jobId, UUID ownerUuid, String dimension, String manifestHash,
                              JobKind kind, String parentJobId, JobState state, PauseReason pauseReason, int cursor, int total,
                              int repairRound, String claimId, MaterialPolicy materialPolicy, String journalFile,
                              String ledgerFile, String lastError, long createdTick, List<String> acceptedRiskIds) {
    public static final int SCHEMA_VERSION = 1;
    public static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9-]{1,64}");
    public static final String JOBS_DIR = "jobs/";
    public static final String JOURNAL_FILE = "journal.bin";
    public static final String LEDGER_FILE = "ledger.bin";
    /** L7 gives up after this many repair rounds (design 03, L7). */
    public static final int MAX_REPAIR_ROUNDS = 3;

    public ConstructionJob {
        Objects.requireNonNull(jobId, "jobId");
        if (!ID_PATTERN.matcher(jobId).matches()) {
            throw new IllegalArgumentException("job id must match " + ID_PATTERN + ": " + jobId);
        }
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(manifestHash, "manifestHash");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(claimId, "claimId");
        Objects.requireNonNull(materialPolicy, "materialPolicy");
        Objects.requireNonNull(journalFile, "journalFile");
        Objects.requireNonNull(ledgerFile, "ledgerFile");
        if ((state == JobState.PAUSED) != (pauseReason != null)) {
            throw new IllegalArgumentException("a pause reason goes with PAUSED and only with it: " + state + "/" + pauseReason);
        }
        if (total < 0 || cursor < 0 || cursor > total) {
            throw new IllegalArgumentException("cursor " + cursor + " must lie in 0.." + total);
        }
        if (repairRound < 0 || repairRound > MAX_REPAIR_ROUNDS) {
            throw new IllegalArgumentException("repairRound " + repairRound + " must lie in 0.." + MAX_REPAIR_ROUNDS);
        }
        lastError = Objects.requireNonNullElse(lastError, "");
        acceptedRiskIds = List.copyOf(Objects.requireNonNullElse(acceptedRiskIds, List.of()));
    }

    public static ConstructionJob create(String jobId, UUID owner, String dimension, String manifestHash, JobKind kind,
                                         String parentJobId, int total, String claimId, MaterialPolicy policy,
                                         long createdTick, List<String> acceptedRiskIds) {
        String dir = JOBS_DIR + jobId + "/";
        return new ConstructionJob(SCHEMA_VERSION, jobId, owner, dimension, manifestHash, kind, parentJobId,
                JobState.PENDING_APPROVAL, null, 0, total, 0, claimId, policy, dir + JOURNAL_FILE, dir + LEDGER_FILE, "",
                createdTick, acceptedRiskIds);
    }

    /** Any move but PAUSE, which needs a reason ({@link #paused}). Leaving PAUSED clears the reason. */
    public ConstructionJob on(JobEvent event) {
        if (event == JobEvent.PAUSE) {
            throw new IllegalArgumentException("use paused(reason) to pause");
        }
        return copy(JobStateMachine.next(state, event), null, cursor, total, repairRound, claimId, lastError);
    }

    public ConstructionJob paused(PauseReason reason) {
        return copy(JobStateMachine.next(state, JobEvent.PAUSE), Objects.requireNonNull(reason, "reason"), cursor, total,
                repairRound, claimId, lastError);
    }

    public ConstructionJob withCursor(int c) {
        return copy(state, pauseReason, c, total, repairRound, claimId, lastError);
    }

    public ConstructionJob withTotal(int t) {
        return copy(state, pauseReason, Math.min(cursor, t), t, repairRound, claimId, lastError);
    }

    public ConstructionJob withRepairRound(int r) {
        return copy(state, pauseReason, cursor, total, r, claimId, lastError);
    }

    public ConstructionJob withLastError(String e) {
        return copy(state, pauseReason, cursor, total, repairRound, claimId, e);
    }

    public ConstructionJob withClaimId(String c) {
        return copy(state, pauseReason, cursor, total, repairRound, c, lastError);
    }

    private ConstructionJob copy(JobState s, PauseReason reason, int c, int t, int r, String claim, String error) {
        return new ConstructionJob(schemaVersion, jobId, ownerUuid, dimension, manifestHash, kind, parentJobId, s, reason, c, t,
                r, claim, materialPolicy, journalFile, ledgerFile, error, createdTick, acceptedRiskIds);
    }
}
