package io.github.khayashi4337.micradrone.chat;

import java.util.List;
import java.util.Locale;

/**
 * Recognises "the CLI answered but is not signed in" inside an error string (P4 task A1): the real
 * CLI answers a {@code claude -p} call made while logged out with a result text like
 * "Not logged in · Please run /login" (measured with an empty Claude config directory), which
 * reaches the build chat as an ordinary failure - retried forever unless it is told apart.
 */
final class ClaudeCliErrors {
    /**
     * Substrings that mark a sign-in problem rather than a transient failure. "not logged in" and
     * "please run /login" come from the measured logged-out answer; "invalid api key" and
     * "authentication_error" are the API-key variants of the same dead end.
     */
    private static final List<String> LOGIN_MARKERS = List.of(
            "not logged in", "please run /login", "invalid api key", "authentication_error");

    private ClaudeCliErrors() {
    }

    /** True when {@code errorMessage} says the CLI is not signed in; case-insensitive. */
    static boolean looksLikeLoginMissing(String errorMessage) {
        if (errorMessage == null) {
            return false;
        }
        String lower = errorMessage.toLowerCase(Locale.ROOT);
        for (String marker : LOGIN_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
