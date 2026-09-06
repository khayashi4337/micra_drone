package io.github.khayashi4337.micradrone.chat;

import java.util.ArrayList;
import java.util.List;

/**
 * Pulls fenced ```code blocks out of an AI chat reply so each one can get its own Insert button.
 * Minecraft-free by design (see PlotGeometry's history for why a class this widely reused should
 * stay that way).
 */
public final class CodeBlockParser {
    private CodeBlockParser() {
    }

    /** One fenced block: {@code language} is the fence-line hint (e.g. "python"), empty if none was given. */
    public record CodeBlock(String language, String code) {
    }

    /**
     * Extracts every fenced block in appearance order. A fence opened but never closed (the reply was
     * cut off, or the model simply forgot) is dropped rather than guessed at.
     */
    public static List<CodeBlock> parse(String text) {
        List<CodeBlock> blocks = new ArrayList<>();
        String[] lines = text.split("\n", -1);

        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.strip();
            if (trimmed.startsWith("```")) {
                String language = trimmed.substring(3).strip();
                int bodyStart = i + 1;
                int closingIndex = -1;
                for (int j = bodyStart; j < lines.length; j++) {
                    if (lines[j].strip().equals("```")) {
                        closingIndex = j;
                        break;
                    }
                }
                if (closingIndex == -1) {
                    break;
                }
                String code = String.join("\n", java.util.Arrays.copyOfRange(lines, bodyStart, closingIndex));
                blocks.add(new CodeBlock(language, code));
                i = closingIndex + 1;
            } else {
                i++;
            }
        }
        return blocks;
    }
}
