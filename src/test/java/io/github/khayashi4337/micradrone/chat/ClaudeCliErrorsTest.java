package io.github.khayashi4337.micradrone.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.ai.StageResult;
import io.github.khayashi4337.micradrone.chat.ClaudeCliBridge.ClaudeCliResult;
import org.junit.jupiter.api.Test;

class ClaudeCliErrorsTest {
    /**
     * The verbatim {@code result} string a logged-out CLI answers a -p call with (measured on the
     * real machine with an empty Claude config directory): is_error=true surfaces it as an
     * ordinary failure, so without recognition the child would be told to retry forever.
     */
    private static final String REAL_LOGGED_OUT = "Not logged in · Please run /login";

    @Test
    void theRealLoggedOutAnswerIsRecognised() {
        assertTrue(ClaudeCliErrors.looksLikeLoginMissing(REAL_LOGGED_OUT));
    }

    @Test
    void anInvalidApiKeyAnswerIsRecognised() {
        assertTrue(ClaudeCliErrors.looksLikeLoginMissing("Invalid API key · Fix external API key"));
    }

    @Test
    void letterCaseAndSurroundingTextDoNotMatter() {
        assertTrue(ClaudeCliErrors.looksLikeLoginMissing("claude CLI exited 1: NOT LOGGED IN"));
        assertTrue(ClaudeCliErrors.looksLikeLoginMissing("request failed: authentication_error"));
        assertTrue(ClaudeCliErrors.looksLikeLoginMissing("x Please run /login now x"));
    }

    @Test
    void ordinaryFailuresAreNotSignInProblems() {
        assertFalse(ClaudeCliErrors.looksLikeLoginMissing("claude CLI timed out after 180s"));
        assertFalse(ClaudeCliErrors.looksLikeLoginMissing("claude CLI exited 1: boom"));
        assertFalse(ClaudeCliErrors.looksLikeLoginMissing(null));
        assertFalse(ClaudeCliErrors.looksLikeLoginMissing(""));
    }

    @Test
    void theStageRunnerFlagsASignInFailureAsLoginMissing() {
        StageResult loggedOut = ClaudeStageRunner.toStageResult(
                new ClaudeCliResult(false, null, "s1", REAL_LOGGED_OUT));
        assertTrue(loggedOut.loginMissing());
        assertFalse(loggedOut.cliMissing(), "a logged-out CLI exists - it is not 'not installed'");

        StageResult timedOut = ClaudeStageRunner.toStageResult(
                new ClaudeCliResult(false, null, "s1", "claude CLI timed out after 180s"));
        assertFalse(timedOut.loginMissing());
        assertFalse(timedOut.cliMissing());

        StageResult absent = ClaudeStageRunner.toStageResult(
                new ClaudeCliResult(false, null, "s1", ClaudeCliBridge.CLI_NOT_FOUND_MESSAGE));
        assertFalse(absent.loginMissing(), "'not installed' stays its own flag");
        assertTrue(absent.cliMissing());
    }
}
