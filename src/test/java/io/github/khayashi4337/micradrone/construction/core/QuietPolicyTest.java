package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.ai.BuildChatFlow;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuietPolicyTest {
    /**
     * The decision table of M2b: every key ChildMessages knows, and whether a panel owner's chat
     * shows it. true = suppressed: the line's text or arguments carry the command path's machinery -
     * a command name (submit/approve/resume/recover), a confirm flag, a manifest hash, a job id or an
     * item registry id. A key absent from this table fails the test, so whoever adds a child-facing
     * line must decide which side it lands on.
     */
    private static final Map<String, Boolean> EXPECTED = expected();

    private static Map<String, Boolean> expected() {
        Map<String, Boolean> t = new HashMap<>();
        t.put(ChildMessages.SUBMIT_OK, true);           // "/micradrone build approve %3$s" - command + hash
        t.put(ChildMessages.SUBMIT_ISSUES, false);      // only the count of problems
        t.put(ChildMessages.SUBMIT_BUSY, false);        // "wait a little" - a plain reason
        t.put(ChildMessages.SUBMIT_BAD_SOURCE, false);  // a read-error detail; the panel path never sends it
        t.put(ChildMessages.APPROVE_OK, true);          // "(しごと job-…)"
        t.put(ChildMessages.PROGRESS, true);            // "しごと job-…: …"
        t.put(ChildMessages.DONE, false);               // "できあがり!" - nothing machine-readable
        t.put(ChildMessages.PARTIAL, false);            // only the unplaced count
        t.put(ChildMessages.CONFLICTS, false);          // only the conflict count
        t.put(ChildMessages.SHORTAGE, true);            // %1$s is the item's registry id (minecraft:stone)
        t.put(ChildMessages.CANCELLED, true);           // "しごと job-…"
        t.put(ChildMessages.RESUMED, true);             // "しごと job-…"
        t.put(ChildMessages.STATUS_LINE, true);         // "job-…: …", and an embedded pause may name a command
        t.put(ChildMessages.NO_JOBS, false);            // "no job is running" - plain
        t.put(ChildMessages.TERRAIN_CONFIRM, true);     // names approve + the confirm-terraform flag
        t.put(ChildMessages.DESTRUCTIVE_CONFIRM, true); // names the confirm-destructive flag
        t.put(ChildMessages.ISSUE_OTHER, false);        // "もんだいが あるよ"
        t.put(ChildMessages.ISSUE_SITE_UNLOADED, false);// "come closer and send again"
        t.put(ChildMessages.DRONE_ARRIVED, false);      // "ドローンが きたよ!"
        t.put(ChildMessages.RECOVER_ASK, true);         // job id + "/micradrone build recover … adopt/discard"
        t.put(ChildMessages.HALTED, false);             // "きろくが かけないので とめたよ" - a plain reason
        for (JobState s : JobState.values()) {
            // a bare state word ("たてているよ"); it only ever rides inside STATUS_LINE, which is suppressed
            t.put(ChildMessages.state(s), false);
        }
        // pause reasons, one by one: the two that name a command are suppressed, the rest are pure reasons
        t.put(ChildMessages.pause(PauseReason.OWNER_OFFLINE), false);
        t.put(ChildMessages.pause(PauseReason.CHUNK_UNLOADED), false);
        t.put(ChildMessages.pause(PauseReason.MATERIALS_MISSING), false);
        t.put(ChildMessages.pause(PauseReason.SERVER_BUSY), false);
        t.put(ChildMessages.pause(PauseReason.RECOVERY_NEEDED), true); // "/micradrone build recover"
        t.put(ChildMessages.pause(PauseReason.USER), false);
        t.put(ChildMessages.pause(PauseReason.SITE_CHANGED), true);    // "/micradrone build resume … skip-conflicts"
        t.put(ChildMessages.pause(PauseReason.NO_ROOM), false);
        // approval rejections, one by one: a reason that tells the child to run submit or to add a
        // confirm flag names command machinery; a pure explanation stays
        t.put(ChildMessages.rejection(ApprovalRejection.NO_PENDING), true);            // "submit してね"
        t.put(ChildMessages.rejection(ApprovalRejection.NOT_OWNER), false);            // "ほかの ひとの けいかくだよ"
        t.put(ChildMessages.rejection(ApprovalRejection.EXPIRED), true);               // "submit してね"
        t.put(ChildMessages.rejection(ApprovalRejection.HASH_MISMATCH), true);         // "submit してね"
        t.put(ChildMessages.rejection(ApprovalRejection.DIMENSION_MISMATCH), false);   // "おなじ せかいで しょうにんしてね"
        t.put(ChildMessages.rejection(ApprovalRejection.REGISTRY_CHANGED), true);      // "submit してね"
        t.put(ChildMessages.rejection(ApprovalRejection.SURVEY_MISMATCH), true);       // "submit してね"
        t.put(ChildMessages.rejection(ApprovalRejection.SURVEY_EXPIRED), true);        // "submit してね"
        t.put(ChildMessages.rejection(ApprovalRejection.RISK_NOT_ACCEPTABLE), false);  // "うけいれられないよ"
        t.put(ChildMessages.rejection(ApprovalRejection.BLOCKING_ISSUES), false);      // "なおさないと たてられない"
        t.put(ChildMessages.rejection(ApprovalRejection.TERRAFORM_UNCONFIRMED), true);   // "confirm-terraform"
        t.put(ChildMessages.rejection(ApprovalRejection.DESTRUCTIVE_UNCONFIRMED), true); // "confirm-destructive"
        t.put(ChildMessages.rejection(ApprovalRejection.ASSEMBLY_NOT_AVAILABLE), false); // "まだ たてられないよ"
        for (ControlResult c : ControlResult.values()) {
            // a short plain answer ("オッケー", "その しごとは ないよ", …) - no machinery in any of them
            t.put(ChildMessages.control(c), false);
        }
        for (IssueCode c : ChildMessages.P4_ISSUES) {
            // every issue line is a pure hiragana reason; the code and id only reach the server log
            t.put(ChildMessages.issue(c), false);
        }
        for (String key : BuildChatFlow.CHAT_MESSAGE_KEYS) {
            // the chat flow's own lines are written child-facing already (chat.says passes the AI's
            // hiragana line through, and the flow - not this policy - owns what the AI may say)
            t.put(key, false);
        }
        return t;
    }

    @Test
    void everyChildFacingKeyHasAnExplicitDecision() {
        for (String key : ChildMessages.allKeys()) {
            assertTrue(EXPECTED.containsKey(key), "no panel decision for " + key);
            assertEquals(EXPECTED.get(key), QuietPolicy.suppressForPanel(key), key);
        }
        for (String key : EXPECTED.keySet()) {
            assertTrue(ChildMessages.allKeys().contains(key),
                    "the table names a key ChildMessages does not know: " + key);
        }
    }

    @Test
    void theLinesTheMvpCaughtInChatAreSuppressed() {
        // run-evidence/p4/p4-mvp-real-001: these lines leaked command machinery into a child's chat
        assertTrue(QuietPolicy.suppressForPanel(ChildMessages.SUBMIT_OK));
        assertTrue(QuietPolicy.suppressForPanel(ChildMessages.TERRAIN_CONFIRM));
        assertTrue(QuietPolicy.suppressForPanel(ChildMessages.APPROVE_OK));
        assertTrue(QuietPolicy.suppressForPanel(ChildMessages.PROGRESS));
        assertTrue(QuietPolicy.suppressForPanel(ChildMessages.STATUS_LINE));
    }

    @Test
    void anUnknownKeyIsNotSuppressed() {
        assertFalse(QuietPolicy.suppressForPanel("micradrone.build.future_line"),
                "a line the policy does not know stays visible");
    }
}
