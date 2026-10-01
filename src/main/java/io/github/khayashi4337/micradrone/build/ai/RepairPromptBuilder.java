package io.github.khayashi4337.micradrone.build.ai;

import java.util.List;
import java.util.Objects;

/**
 * Builds the second prompt sent to the AI when the server rejected a plan (P4 task M1): it restates
 * the one-fence reply rule, lists the server's issue lines as bullets, and appends the rejected JSON
 * so the model returns a corrected plan in the same shape.
 */
public final class RepairPromptBuilder {
    /** Only the first this-many issue lines are sent; a rejected plan rarely needs more to be fixed. */
    public static final int MAX_ISSUE_LINES = 10;
    /** One issue line is cut at this many chars so a huge message cannot crowd out the JSON. */
    public static final int MAX_ISSUE_LINE_CHARS = 200;

    private RepairPromptBuilder() {
    }

    public static String build(String previousJson, List<String> issueLines) {
        Objects.requireNonNull(previousJson, "previousJson");
        Objects.requireNonNull(issueLines, "issueLines");
        if (issueLines.isEmpty()) {
            throw new IllegalArgumentException("issueLines is empty");
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("さっきの計画に問題があった。直したJSONを、同じ規則(返答は```jsonブロックをちょうど1つ)で返して。\n");
        prompt.append(BuildPromptBuilder.WAKACHI_RULE).append("\n\n");
        prompt.append("問題:\n");
        int count = Math.min(issueLines.size(), MAX_ISSUE_LINES);
        for (int i = 0; i < count; i++) {
            String line = Objects.requireNonNull(issueLines.get(i), "issueLines[" + i + "]");
            if (line.length() > MAX_ISSUE_LINE_CHARS) {
                line = line.substring(0, MAX_ISSUE_LINE_CHARS);
            }
            prompt.append("- ").append(line).append('\n');
        }
        prompt.append("\n直す前の計画のJSON:\n```json\n").append(previousJson).append("\n```\n");
        return prompt.toString();
    }
}
