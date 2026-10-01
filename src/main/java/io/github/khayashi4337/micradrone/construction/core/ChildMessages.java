package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.ai.BuildChatFlow;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The translation keys of everything a child sees from the construction runtime (F-18): the texts live in the lang
 * files, never in code. ChildMessagesTest keeps this list and both lang files equal.
 */
public final class ChildMessages {
    public static final String PREFIX = "micradrone.build.";
    public static final String SUBMIT_OK = PREFIX + "submit.ok";
    public static final String SUBMIT_ISSUES = PREFIX + "submit.issues";
    public static final String SUBMIT_BUSY = PREFIX + "submit.busy";
    public static final String SUBMIT_BAD_SOURCE = PREFIX + "submit.bad_source";
    public static final String APPROVE_OK = PREFIX + "approve.ok";
    public static final String PROGRESS = PREFIX + "progress";
    public static final String DONE = PREFIX + "done";
    public static final String PARTIAL = PREFIX + "partial";
    public static final String CONFLICTS = PREFIX + "conflicts";
    public static final String SHORTAGE = PREFIX + "shortage";
    public static final String CANCELLED = PREFIX + "cancelled";
    public static final String RESUMED = PREFIX + "resumed";
    public static final String STATUS_LINE = PREFIX + "status.line";
    public static final String NO_JOBS = PREFIX + "status.none";
    public static final String TERRAIN_CONFIRM = PREFIX + "confirm.terrain";
    public static final String DESTRUCTIVE_CONFIRM = PREFIX + "confirm.destructive";
    public static final String ISSUE_OTHER = PREFIX + "issue.other";
    /** E-SITE-BLOCKED keyed "unloaded": the spot is not loaded yet, not blocked by an unmoving block. */
    public static final String ISSUE_SITE_UNLOADED = PREFIX + "issue.site_unloaded";
    public static final String DRONE_ARRIVED = PREFIX + "drone.arrived";
    /** A job whose log after the last durable point waits for its owner's answer (adopt or discard). Task 24. */
    public static final String RECOVER_ASK = PREFIX + "recover.ask";
    /** The rollback preview (F-5's confirmation stand-in): how many blocks come out, and the confirm form. Task 28. */
    public static final String ROLLBACK_ASK = PREFIX + "rollback.ask";
    /** The one line for a runtime that stopped because its records could not be written (Task 25). */
    public static final String HALTED = PREFIX + "halted";
    public static final Set<IssueCode> P4_ISSUES = EnumSet.of(IssueCode.E_SITE_BLOCKED, IssueCode.E_SITE_CHANGED,
            IssueCode.E_OUT_OF_BOUNDS, IssueCode.E_BLOCK_FORBIDDEN, IssueCode.E_MATERIAL_UNKNOWN, IssueCode.E_MATERIAL_SHORT,
            IssueCode.E_TERRAFORM_UNCONFIRMED, IssueCode.E_REPLACE_UNCONFIRMED, IssueCode.E_CLAIM_OVERLAP,
            IssueCode.E_CLAIM_LIMIT, IssueCode.E_CLAIM_INVALID, IssueCode.E_REGISTRY_VERSION, IssueCode.E_TEMPLATE_UNVERIFIED,
            IssueCode.E_SITE_MISSING, IssueCode.E_PARAM_RANGE, IssueCode.E_UNKNOWN_PART);
    // FIXED also lists every micradrone.build.chat.* key the build-chat flow can emit (P4 task M3):
    // the flow carries them as plain strings (build.* may not import construction.core), so this
    // addAll is the one place the two sides are tied together for the ChildMessagesTest checks.
    private static final Set<String> FIXED = fixedKeys();

    private static Set<String> fixedKeys() {
        Set<String> out = new HashSet<>(Set.of(SUBMIT_OK, SUBMIT_ISSUES, SUBMIT_BUSY,
                SUBMIT_BAD_SOURCE, APPROVE_OK, PROGRESS, DONE, PARTIAL, CONFLICTS, SHORTAGE,
                CANCELLED, RESUMED, STATUS_LINE, NO_JOBS, TERRAIN_CONFIRM, DESTRUCTIVE_CONFIRM,
                ISSUE_OTHER, ISSUE_SITE_UNLOADED, DRONE_ARRIVED, RECOVER_ASK, ROLLBACK_ASK, HALTED));
        out.addAll(BuildChatFlow.CHAT_MESSAGE_KEYS);
        return out;
    }
    /** An issue's id carries its key after this marker (see {@code Issue.of}). */
    private static final String ID_KEY_MARKER = "#";

    private ChildMessages() {
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public static String state(JobState s) {
        return PREFIX + "state." + lower(s.name());
    }

    public static String pause(PauseReason r) {
        return PREFIX + "pause." + lower(r.name());
    }

    public static String rejection(ApprovalRejection r) {
        return PREFIX + "reject." + lower(r.name());
    }

    public static String control(ControlResult c) {
        return PREFIX + "control." + lower(c.name());
    }

    public static String issue(IssueCode c) {
        return P4_ISSUES.contains(c) ? PREFIX + "issue." + lower(c.label().replace('-', '_')) : ISSUE_OTHER;
    }

    /**
     * The line a child sees for an issue: the translated text only. The issue's code and id go to the server log and to
     * {@code /micradrone build status <jobId> --debug}, never into a child's chat.
     */
    public static MessageKey issueLine(Issue issue) {
        // a site that is only not loaded yet is not "a block in the way": the child is asked to come closer
        if (issue.code() == IssueCode.E_SITE_BLOCKED
                && issue.id().endsWith(ID_KEY_MARKER + SafetyEnvelope.KEY_UNLOADED)) {
            return MessageKey.of(ISSUE_SITE_UNLOADED);
        }
        return MessageKey.of(issue(issue.code()));
    }

    /** "Almost done, N places could not be built": a count, not the machine-readable reason in {@code lastError}. */
    public static MessageKey partial(int unplaced) {
        return MessageKey.of(PARTIAL, unplaced);
    }

    public static Set<String> allKeys() {
        Set<String> out = new TreeSet<>(FIXED);
        for (JobState s : JobState.values()) {
            out.add(state(s));
        }
        for (PauseReason r : PauseReason.values()) {
            out.add(pause(r));
        }
        for (ApprovalRejection r : ApprovalRejection.values()) {
            out.add(rejection(r));
        }
        for (ControlResult c : ControlResult.values()) {
            out.add(control(c));
        }
        for (IssueCode c : P4_ISSUES) {
            out.add(issue(c));
        }
        return out;
    }
}
