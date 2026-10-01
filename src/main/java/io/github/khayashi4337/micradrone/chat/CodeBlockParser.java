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
     * A fenced block plus where it sits in the source text: {@code fenceStart} is the offset of the
     * opening fence's line, so a caller can recover the prose before the block.
     */
    public record LocatedBlock(String language, String code, int fenceStart) {
    }

    /**
     * Extracts every fenced block in appearance order. A fence opened but never closed (the reply was
     * cut off, or the model simply forgot) is dropped rather than guessed at.
     */
    public static List<CodeBlock> parse(String text) {
        List<CodeBlock> blocks = new ArrayList<>();
        for (LocatedBlock b : parseLocated(text)) {
            blocks.add(new CodeBlock(b.language(), b.code()));
        }
        return blocks;
    }

    /**
     * Same scan as {@link #parse}, but each block carries the offset of its opening fence line so the
     * caller can recover the text around it.
     */
    public static List<LocatedBlock> parseLocated(String text) {
        List<LocatedBlock> blocks = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        int[] lineStart = new int[lines.length];
        for (int i = 1; i < lines.length; i++) {
            lineStart[i] = lineStart[i - 1] + lines[i - 1].length() + 1;
        }

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
                blocks.add(new LocatedBlock(language, code, lineStart[i]));
                i = closingIndex + 1;
            } else {
                i++;
            }
        }
        return blocks;
    }
}
