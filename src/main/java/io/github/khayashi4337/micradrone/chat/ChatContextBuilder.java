package io.github.khayashi4337.micradrone.chat;

import java.util.List;
import java.util.Optional;

/**
 * Assembles the text sent to claude -p for one chat turn: the player's question plus whatever
 * context the IDE already knows (current script, a command-reference excerpt, the last runtime
 * error, and any pending world-region reference) - Minecraft-free so it stays unit-testable
 * without a real IdeScreen.
 */
public final class ChatContextBuilder {
    private ChatContextBuilder() {
    }

    /**
     * @param scriptText                 the editor's current (possibly unsaved) contents
     * @param commandReferenceExcerpt    CommandsHelpDoc text to ground the reply in real commands
     * @param logLines                   the controller's log buffer; the last {@code "error: "}-prefixed
     *                                    line, if any, becomes the "last error" context
     * @param pendingRegionReferenceText RegionSelectionState#consumeAsText's result, if any
     */
    public record ChatContext(
            String scriptText,
            String commandReferenceExcerpt,
            List<String> logLines,
            Optional<String> pendingRegionReferenceText) {
    }

    public static String build(String userQuestion, ChatContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("現在のスクリプト:\n```\n");
        sb.append(context.scriptText().isBlank() ? "(空)" : context.scriptText());
        sb.append("\n```\n\n");

        sb.append("コマンドリファレンス抜粋:\n");
        sb.append(context.commandReferenceExcerpt());
        sb.append("\n\n");

        Optional<String> lastError = lastError(context.logLines());
        if (lastError.isPresent()) {
            sb.append("直前の実行エラー: ").append(lastError.get()).append("\n\n");
        }

        context.pendingRegionReferenceText().ifPresent(region ->
                sb.append("参照範囲(ワールド座標): ").append(region).append("\n\n"));

        sb.append("質問:\n").append(userQuestion);
        return sb.toString();
    }

    /** The most recent {@code "error: "}-prefixed log line, with that prefix stripped. */
    static Optional<String> lastError(List<String> logLines) {
        for (int i = logLines.size() - 1; i >= 0; i--) {
            String line = logLines.get(i);
            if (line.startsWith("error: ")) {
                return Optional.of(line.substring("error: ".length()));
            }
        }
        return Optional.empty();
    }
}
