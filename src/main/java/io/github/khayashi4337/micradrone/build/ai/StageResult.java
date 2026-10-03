package io.github.khayashi4337.micradrone.build.ai;

/**
 * One AI round trip as the build-chat flow sees it (P4 task M3). {@code cliMissing} singles out
 * "the CLI is not installed" from ordinary failures because the child's next step differs (ask a
 * grown-up to install Claude Code vs. just retrying); A1's {@code loginMissing} singles out "the
 * CLI answered but is not signed in", which no retry can ever fix.
 */
public record StageResult(boolean success, String text, String error, boolean cliMissing,
        boolean loginMissing) {
    /** The pre-A1 shape: a runner that never checked for a sign-in problem reports false. */
    public StageResult(boolean success, String text, String error, boolean cliMissing) {
        this(success, text, error, cliMissing, false);
    }
}
