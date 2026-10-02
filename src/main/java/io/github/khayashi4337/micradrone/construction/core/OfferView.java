package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.Issue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The offer table sent to the client (M2): a flat {@code Map<String,Object>} view of a
 * {@link SubmitOutcome}, shaped like {@link JobViews} so {@code chat.MiniJson} writes it directly.
 * {@code issues} carry {@code {childKey, message}} pairs - the childKey names the child-facing line,
 * the message is the technical text a fixing AI reply needs; the screen never shows it.
 */
public final class OfferView {
    /** Vanilla runs twenty ticks a second; the client sees the ETA in seconds. */
    private static final long TICKS_PER_SECOND = 20L;
    /**
     * A submission the server turned down before an approval could exist (unreadable plan text, an
     * upload shape the MVP cannot serve, a refused approve). Not a {@link SubmitOutcome} state:
     * nothing was ever submitted.
     */
    public static final String REJECTED = "REJECTED";

    private OfferView() {
    }

    /**
     * One issue as the offer carries it. {@code childKey} may be null: a payload-shape refusal is a
     * protocol violation with no child-facing line, only the technical {@code message}.
     */
    public record Entry(String childKey, String message) {
        public static Entry of(Issue issue) {
            return new Entry(ChildMessages.issueLine(issue).key(), issue.message());
        }
    }

    /**
     * The offer table of one submission outcome. {@code blocks} is the compiled manifest's placement
     * count; the caller (the runtime) knows it, the outcome does not carry it. {@code materialPolicy}
     * (Task 27b) names the policy the approval would decide - the panel reads it to say where the
     * materials come from; only an OFFERED doc carries a name, everything else writes null.
     */
    public static Map<String, Object> tree(SubmitOutcome outcome, int blocks, MaterialPolicy materialPolicy) {
        PendingApproval pending = outcome.pending();
        return base(outcome.state(), pending == null ? null : pending.manifestHash(), blocks,
                outcome.etaTicks(), outcome.replacements(), materialPolicy,
                entryTrees(ofIssues(outcome.issues())));
    }

    /** The table for a refusal that never became a submission (the {@link #REJECTED} state). */
    public static Map<String, Object> rejectedTree(List<Entry> issues) {
        return base(REJECTED, null, 0, 0L, null, null, entryTrees(issues));
    }

    private static List<Entry> ofIssues(List<Issue> issues) {
        List<Entry> entries = new ArrayList<>(issues.size());
        for (Issue issue : issues) {
            entries.add(Entry.of(issue));
        }
        return entries;
    }

    private static List<Object> entryTrees(List<Entry> entries) {
        List<Object> out = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("childKey", e.childKey());
            t.put("message", e.message());
            out.add(t);
        }
        return out;
    }

    private static Map<String, Object> base(String state, String hash, int blocks, long etaTicks,
            ReplacementSummary replacements, MaterialPolicy materialPolicy, List<Object> issues) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("state", state);
        t.put("hash", hash);
        t.put("blocks", (long) blocks);
        t.put("etaSeconds", etaTicks / TICKS_PER_SECOND);
        t.put("terrainCut", replacements == null ? 0L : (long) replacements.terrainCut());
        t.put("terrainFill", replacements == null ? 0L : (long) replacements.terrainFill());
        t.put("fluids", replacements == null ? 0L : (long) replacements.fluids());
        t.put("leaves", replacements == null ? 0L : (long) replacements.leaves());
        t.put("emptyContainers", replacements == null ? 0L : (long) replacements.emptyContainers());
        t.put("needsTerrainConfirm", replacements != null && replacements.needsTerraformConfirm());
        t.put("needsDestructiveConfirm", replacements != null && replacements.needsDestructiveConfirm());
        t.put("materialPolicy", materialPolicy == null ? null : materialPolicy.name());
        t.put("issues", issues);
        return t;
    }
}
