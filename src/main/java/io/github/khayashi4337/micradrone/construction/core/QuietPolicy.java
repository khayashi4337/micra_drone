package io.github.khayashi4337.micradrone.construction.core;

import java.util.Set;

/**
 * Which chat lines a panel owner never sees (M2b). The MVP check found the command path's
 * machinery - {@code /micradrone build approve <hash>}, the {@code confirm-terraform} flag,
 * {@code job-551-0} - bleeding through the child's chat behind the panel, which already shows
 * the same facts without them. A line is suppressed when its text or its arguments carry a
 * command name, a manifest hash, a job id or an item registry id; QuietPolicyTest decides every
 * key ChildMessages knows, so a new key cannot sneak in undecided.
 */
public final class QuietPolicy {
    private QuietPolicy() {
    }

    /** The keys carrying command machinery; everything else a child may see stays visible. */
    private static final Set<String> PANEL_QUIET = Set.of(
            ChildMessages.SUBMIT_OK,           // "/micradrone build approve <hash>"
            ChildMessages.TERRAIN_CONFIRM,     // "approve に confirm-terraform を つけてね"
            ChildMessages.DESTRUCTIVE_CONFIRM, // "confirm-destructive を つけてね"
            ChildMessages.APPROVE_OK,          // "(しごと job-…)"
            ChildMessages.PROGRESS,            // "しごと job-…: …"
            ChildMessages.STATUS_LINE,         // "job-…: <state>…", pauses may name commands
            ChildMessages.CANCELLED,           // "しごと job-…"
            ChildMessages.RESUMED,             // "しごと job-…"
            ChildMessages.RECOVER_ASK,         // job id + "/micradrone build recover"
            ChildMessages.ROLLBACK_ASK,        // claim id + "/micradrone build rollback … confirm"
            ChildMessages.SHORTAGE,            // the missing item's name is a registry id
            ChildMessages.rejection(ApprovalRejection.NO_PENDING),            // "submit してね"
            ChildMessages.rejection(ApprovalRejection.EXPIRED),               // "submit してね"
            ChildMessages.rejection(ApprovalRejection.HASH_MISMATCH),         // "submit してね"
            ChildMessages.rejection(ApprovalRejection.REGISTRY_CHANGED),      // "submit してね"
            ChildMessages.rejection(ApprovalRejection.SURVEY_MISMATCH),       // "submit してね"
            ChildMessages.rejection(ApprovalRejection.SURVEY_EXPIRED),        // "submit してね"
            ChildMessages.rejection(ApprovalRejection.TERRAFORM_UNCONFIRMED),   // the flag's name
            ChildMessages.rejection(ApprovalRejection.DESTRUCTIVE_UNCONFIRMED), // the flag's name
            ChildMessages.pause(PauseReason.RECOVERY_NEEDED),   // "/micradrone build recover"
            ChildMessages.pause(PauseReason.SITE_CHANGED));     // "/micradrone build resume …"

    /** True when a panel owner's chat must not show the line this key builds. */
    public static boolean suppressForPanel(String messageKey) {
        return PANEL_QUIET.contains(messageKey);
    }
}
