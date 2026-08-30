package io.github.khayashi4337.micradrone.chat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Keeps a set of breakpoint line numbers attached to the statements they were set on when the
 * script text they refer to is edited - see {@link #retarget}. Minecraft-free (built on
 * {@link LineDiff}, the same whole-line diff the AI chat review already uses) so this stays
 * unit-testable; the IDE screen owns the actual breakpoint field and the network side-effect of
 * telling the server about a change.
 */
public final class BreakpointRetargeting {
    private BreakpointRetargeting() {
    }

    /**
     * Maps each of {@code breakpoints} (1-based line numbers in {@code previousText}) to where that
     * same line landed in {@code newText}, via a whole-line diff. A line that survived unchanged
     * moves to its new number; a line that was itself edited or deleted has no single line to
     * attribute the breakpoint to any more, so it's dropped rather than guessed at. Returns
     * {@code breakpoints} itself, unexamined, when the text didn't actually change.
     */
    public static Set<Integer> retarget(Set<Integer> breakpoints, String previousText, String newText) {
        if (breakpoints.isEmpty() || newText.equals(previousText)) {
            return breakpoints;
        }
        Map<Integer, Integer> oldLineToNewLine = new HashMap<>();
        int oldLine = 0;
        int newLine = 0;
        for (LineDiff.Line line : LineDiff.between(previousText, newText).lines()) {
            switch (line.kind()) {
                case SAME -> {
                    oldLine++;
                    newLine++;
                    oldLineToNewLine.put(oldLine, newLine);
                }
                case REMOVED -> oldLine++;
                case ADDED -> newLine++;
            }
        }
        Set<Integer> retargeted = new HashSet<>();
        for (int oldBreakpointLine : breakpoints) {
            Integer mapped = oldLineToNewLine.get(oldBreakpointLine);
            if (mapped != null) {
                retargeted.add(mapped);
            }
        }
        return retargeted;
    }
}
