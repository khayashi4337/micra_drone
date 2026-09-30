package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.Issue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small flat {@code Map<String,Object>} views of the runtime state, shared by the commands, the runtime's own
 * queries and the devkit so every consumer shows the same JSON. The maps are plain data that
 * {@code chat.MiniJson} writes directly: keys are stable machine-readable names, numbers are {@code Long}, and
 * {@code null} fields are written through so a reader can rely on the schema without guessing types.
 */
public final class JobViews {
    private JobViews() {
    }

    /** The state name used for the owner who has never submitted (or whose outcome was dropped). */
    public static final String NO_SUBMISSION = "NONE";

    public static Map<String, Object> statusTree(JobStatus s) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("jobId", s.jobId());
        t.put("owner", s.owner().toString());
        t.put("kind", s.kind().name());
        t.put("state", s.state().name());
        t.put("pause", s.shownPause() == null ? null : s.shownPause().name());
        t.put("cursor", (long) s.cursor());
        t.put("total", (long) s.total());
        t.put("repairRound", (long) s.repairRound());
        t.put("conflicts", (long) s.conflicts());
        t.put("skipped", (long) s.skipped());
        t.put("lastError", s.lastError());
        t.put("claimId", s.claimId());
        t.put("dimension", s.dimension());
        return t;
    }

    /** All running/known jobs as a stable {@code {"jobs": [...]}} list. */
    public static Map<String, Object> jobsTree(List<JobStatus> statuses) {
        List<Object> jobs = new ArrayList<>();
        for (JobStatus s : statuses) {
            jobs.add(statusTree(s));
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("jobs", jobs);
        return t;
    }

    /**
     * A submit outcome as JSON. A {@code null} outcome means "no submission yet" and renders as
     * {@code {"state": "NONE"}} so the devkit does not have to special-case missing entries.
     */
    public static Map<String, Object> submitTree(SubmitOutcome outcome) {
        Map<String, Object> t = new LinkedHashMap<>();
        if (outcome == null) {
            t.put("state", NO_SUBMISSION);
            return t;
        }
        t.put("state", outcome.state());
        PendingApproval p = outcome.pending();
        t.put("hash", p == null ? null : p.manifestHash());
        t.put("dimension", p == null ? null : p.dimension());
        t.put("expiresTick", p == null ? null : p.expiresTick());
        t.put("surveyDigest", p == null ? null : p.surveyDigest());
        List<Object> issues = new ArrayList<>();
        for (Issue i : outcome.issues()) {
            issues.add(issueTree(i));
        }
        t.put("issues", issues);
        t.put("replacements", replacementsTree(outcome.replacements()));
        t.put("etaTicks", outcome.etaTicks());
        return t;
    }

    /** What an approval would replace; {@code null} renders as {@code null}. */
    public static Map<String, Object> replacementsTree(ReplacementSummary r) {
        if (r == null) {
            return null;
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("fluids", (long) r.fluids());
        t.put("leaves", (long) r.leaves());
        t.put("emptyContainers", (long) r.emptyContainers());
        t.put("terrainCut", (long) r.terrainCut());
        t.put("terrainFill", (long) r.terrainFill());
        List<Object> sample = new ArrayList<>();
        for (IntPos p : r.destructiveSample()) {
            sample.add(List.of((long) p.x(), (long) p.y(), (long) p.z()));
        }
        t.put("destructiveSample", sample);
        return t;
    }

    /**
     * An issue as JSON. Everything a log parser or a parent UI needs is present: the stable {@code id}, the
     * {@code code} label (with dashes, matching the design docs), the {@code severity}, whether it is an
     * acceptable risk, the {@code subjects}, the developer-facing {@code message}, the {@code data} map, and
     * the child-facing translation key {@code childKey}.
     */
    public static Map<String, Object> issueTree(Issue i) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", i.id());
        t.put("code", i.code().label());
        t.put("severity", i.severity().name());
        t.put("acceptable", i.acceptable());
        t.put("subjects", i.subjects());
        t.put("message", i.message());
        t.put("data", i.data());
        t.put("childKey", ChildMessages.issue(i.code()));
        return t;
    }
}
