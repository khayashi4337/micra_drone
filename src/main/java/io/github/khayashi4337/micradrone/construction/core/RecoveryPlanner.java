package io.github.khayashi4337.micradrone.construction.core;

import java.util.EnumSet;
import java.util.Set;

/**
 * What a saved job becomes at load (04 F-2): unreadable files mean RECOVERY_NEEDED for a live job (never a silent
 * re-run); every other loaded job, finished ones included, is recovered from the write-ahead log first (JobService), and
 * a job that was moving comes back paused until its owner is here.
 */
public final class RecoveryPlanner {
    private static final Set<JobState> MOVING = EnumSet.of(JobState.QUEUED, JobState.RUNNING, JobState.VERIFYING,
            JobState.REPAIRING, JobState.ASSEMBLING);

    private RecoveryPlanner() {
    }

    public static RecoveryDecision decide(ConstructionJob saved, JobLoad load) {
        boolean live = !saved.state().terminal();
        if (load instanceof JobLoad.Broken) {
            return new RecoveryDecision(live ? needsRecovery(saved) : saved, load, false);
        }
        // a finished job too: the log may hold a write the world lost, or a settlement its ledger still owes
        boolean fromLog = saved.state() != JobState.PENDING_APPROVAL;
        ConstructionJob job = MOVING.contains(saved.state()) ? saved.paused(PauseReason.OWNER_OFFLINE) : saved;
        return new RecoveryDecision(job, load, fromLog);
    }

    private static ConstructionJob needsRecovery(ConstructionJob j) {
        if (j.state() == JobState.PAUSED) {
            // no PAUSED -> PAUSED move exists in the state machine: through QUEUED and back with the new reason
            return j.on(JobEvent.RESUME).paused(PauseReason.RECOVERY_NEEDED);
        }
        if (j.state() == JobState.PENDING_APPROVAL) {
            return j.on(JobEvent.FAIL).withLastError("unreadable files before admission");
        }
        return j.paused(PauseReason.RECOVERY_NEEDED);
    }

    /**
     * Every program position before the cursor ended in a journal record (a put's under its ledger key, a removal's under
     * its position) or a skip record (the Task 8 invariant).
     */
    public static boolean settledUpTo(JobRecord r, int cursor) {
        return settledUpTo(r, cursor, Set.of());
    }

    /**
     * The same, where the positions the write-ahead log explains count as settled too (their lost writes are placed again
     * by the re-walk). A position before the saved cursor that neither the files nor the log account for means files
     * that do not belong together: never re-run silently.
     */
    public static boolean settledUpTo(JobRecord r, int cursor, Set<Integer> explained) {
        JobProgram p = r.program();
        for (int i = 0; i < Math.min(cursor, p.size()); i++) {
            if (explained.contains(i)) {
                continue;
            }
            int journalKey = p.isRestore(i) ? JournalRecord.restoreIndex(i) : p.put(i).ledgerKey();
            int skipKey = p.isRestore(i) ? JournalRecord.restoreIndex(i) : p.put(i).index();
            if (r.journal().at(journalKey).isEmpty() && !r.outcome().isSkipped(skipKey)) {
                return false;
            }
        }
        return cursor <= p.size();
    }
}
