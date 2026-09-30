package io.github.khayashi4337.micradrone.build.ai;

/**
 * One AI round trip as the build-chat flow sees it (P4 task M3). {@code cliMissing} singles out
 * "the CLI is not installed" from ordinary failures because the child's next step differs (ask a
 * grown-up to install Claude Code vs. just retrying).
 */
public record StageResult(boolean success, String text, String error, boolean cliMissing) {
}
