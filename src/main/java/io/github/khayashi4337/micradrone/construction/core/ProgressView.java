package io.github.khayashi4337.micradrone.construction.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One job's progress as the client sees it (M2): a flat {@code Map<String,Object>} table like
 * {@link JobViews}'s, trimmed to what a progress display needs. {@code percent} is the integer share
 * of placed blocks; {@code done}/{@code partial} mark the two endings that produced a result.
 * {@code kind} and {@code claimId} (M5) let the panel tell which job a document belongs to and what
 * the もとにもどす button would roll back - neither ever reaches a child-facing line.
 */
public final class ProgressView {
    private ProgressView() {
    }

    /**
     * The running document. {@code inventoryAllowed} (Task 27a) is the claim's current supply switch,
     * so the panel can tell whether the owner already allowed their things to be used.
     */
    public static Map<String, Object> tree(JobStatus s, boolean inventoryAllowed) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("jobId", s.jobId());
        t.put("kind", s.kind().name());
        t.put("claimId", s.claimId());
        t.put("state", s.state().name());
        t.put("cursor", (long) s.cursor());
        t.put("total", (long) s.total());
        t.put("percent", percent(s.cursor(), s.total()));
        t.put("pause", s.shownPause() == null ? null : s.shownPause().name());
        t.put("inventoryAllowed", inventoryAllowed);
        t.put("unrepaired", (long) s.unrepaired());
        t.put("conflicts", (long) s.conflicts());
        t.put("done", s.state() == JobState.VERIFIED);
        t.put("partial", s.state() == JobState.PARTIAL);
        return t;
    }

    /**
     * The same document built from the job itself, for the moment its record is already gone: a finished rollback releases its claim
     * and the service drops the job's record, so no {@link JobStatus} exists to read. {@code unrepaired} and {@code conflicts} are
     * only known to the record, so they read 0 here (the final document of a VERIFIED job has none anyway).
     */
    public static Map<String, Object> finalTree(ConstructionJob job, boolean inventoryAllowed) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("jobId", job.jobId());
        t.put("kind", job.kind().name());
        t.put("claimId", job.claimId());
        t.put("state", job.state().name());
        t.put("cursor", (long) job.cursor());
        t.put("total", (long) job.total());
        t.put("percent", percent(job.cursor(), job.total()));
        t.put("pause", job.pauseReason() == null ? null : job.pauseReason().name());
        t.put("inventoryAllowed", inventoryAllowed);
        t.put("unrepaired", 0L);
        t.put("conflicts", 0L);
        t.put("done", job.state() == JobState.VERIFIED);
        t.put("partial", job.state() == JobState.PARTIAL);
        return t;
    }

    /** Integer percent of placed blocks; an empty job is 0%, never a division by zero. */
    static long percent(int cursor, int total) {
        return total == 0 ? 0L : cursor * 100L / total;
    }
}
