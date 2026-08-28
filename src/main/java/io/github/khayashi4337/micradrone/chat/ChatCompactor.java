package io.github.khayashi4337.micradrone.chat;

import java.util.List;

/**
 * The manual compact fallback. SPK-1 (docs/investigations/spk1_claude_cli_behavior.md) found
 * claude -p only exposes {@code --autocompact <threshold>}, not an on-demand "summarize now"
 * command, so this mod supplies its own: ask the model to summarize, then start the next turn as
 * a brand new CLI session seeded with that summary. Starting fresh (rather than summarizing
 * within the same --resume session) is what actually shrinks the per-turn cost SPK-1 measured -
 * summarizing inside the old session would still pay to re-read its whole cached history.
 */
public final class ChatCompactor {
    /** Sent to claude -p, within the still-open old session, to produce the summary text. */
    public static final String COMPACT_REQUEST_PROMPT =
            "ここまでの会話の要点を、今後この話題を続けるのに必要な情報だけ残して簡潔に要約してください。要約以外の文章は書かないでください。";

    private ChatCompactor() {
    }

    /**
     * Drops the old CLI session (a new --session-id starts clean on the next send) and replaces
     * the local transcript with a single "summary" message carrying {@code summaryText} forward.
     */
    public static void applySummary(ChatSession session, String summaryText) {
        session.setCliSessionId(null);
        session.replaceMessages(List.of(new ChatMessage(ChatMessage.ROLE_SUMMARY, summaryText, System.currentTimeMillis())));
    }
}
