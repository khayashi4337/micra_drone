package io.github.khayashi4337.micradrone.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Line-level diff between the script in the editor and the script the AI proposes, rendered the
 * way Cursor's "Apply" and a git diff do: unchanged lines as they are, removed lines kept in place
 * (shown red by the editor), added lines inserted (shown green). The player reviews that merged
 * view in the editor itself, then Accepts (the proposal becomes the script) or Rejects (the
 * original comes back) - instead of a blind Insert that silently overwrote whatever was there,
 * which was the real-machine complaint: you couldn't see what the reply appended to, or what an
 * overwrite would throw away.
 *
 * <p>Standard longest-common-subsequence diff on whole lines; scripts here are at most a few
 * hundred lines, so the O(n*m) table is nothing. Minecraft-free and unit-tested.
 */
public final class LineDiff {
    public enum Kind { SAME, REMOVED, ADDED }

    /** One row of the merged review view. */
    public record Line(String text, Kind kind) {
    }

    /**
     * One contiguous block of change in the merged view (1-based, inclusive line numbers) - the
     * unit a player can reject on its own, like a hunk in a git diff / a change block in Cursor.
     */
    public record Hunk(int firstLine, int lastLine) {
    }

    private final List<Line> lines;

    private LineDiff(List<Line> lines) {
        this.lines = Collections.unmodifiableList(lines);
    }

    public static LineDiff between(String original, String proposed) {
        List<String> a = splitLines(original);
        List<String> b = splitLines(proposed);
        int n = a.size();
        int m = b.size();
        // lcs[i][j] = length of the LCS of a[i..] and b[j..]
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Line> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) {
                out.add(new Line(a.get(i), Kind.SAME));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.add(new Line(a.get(i), Kind.REMOVED));
                i++;
            } else {
                out.add(new Line(b.get(j), Kind.ADDED));
                j++;
            }
        }
        while (i < n) {
            out.add(new Line(a.get(i++), Kind.REMOVED));
        }
        while (j < m) {
            out.add(new Line(b.get(j++), Kind.ADDED));
        }
        return new LineDiff(out);
    }

    /** The merged review view, top to bottom. */
    public List<Line> lines() {
        return lines;
    }

    /** The merged view as editor text - what the editor shows while the player reviews. */
    public String mergedText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i).text());
        }
        return sb.toString();
    }

    /** 1-based line numbers (in the merged view) of the given kind - what the editor colors. */
    public List<Integer> lineNumbersOf(Kind kind) {
        List<Integer> numbers = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).kind() == kind) {
                numbers.add(i + 1);
            }
        }
        return numbers;
    }

    public boolean hasChanges() {
        return lines.stream().anyMatch(line -> line.kind() != Kind.SAME);
    }

    /** The change blocks, top to bottom: maximal runs of non-SAME lines. */
    public List<Hunk> hunks() {
        List<Hunk> hunks = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            boolean changed = lines.get(i).kind() != Kind.SAME;
            if (changed && start < 0) {
                start = i;
            } else if (!changed && start >= 0) {
                hunks.add(new Hunk(start + 1, i));
                start = -1;
            }
        }
        if (start >= 0) {
            hunks.add(new Hunk(start + 1, lines.size()));
        }
        return hunks;
    }

    /**
     * The review with hunk {@code index} (see {@link #hunks}) turned down: its removed lines are
     * kept as they were, its added lines are dropped. Everything else stays under review, so the
     * player can reject the blocks they dislike one by one and then accept the rest in one go.
     */
    public LineDiff rejectHunk(int index) {
        Hunk hunk = hunks().get(index);
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            int number = i + 1;
            if (number < hunk.firstLine() || number > hunk.lastLine()) {
                out.add(line);
            } else if (line.kind() == Kind.REMOVED) {
                out.add(new Line(line.text(), Kind.SAME));
            }
            // ADDED lines inside the hunk are simply left out.
        }
        return new LineDiff(out);
    }

    /** What the script becomes if every remaining change is accepted: all lines except the removed ones. */
    public String acceptedText() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Line line : lines) {
            if (line.kind() == Kind.REMOVED) {
                continue;
            }
            if (!first) {
                sb.append('\n');
            }
            first = false;
            sb.append(line.text());
        }
        return sb.toString();
    }

    /** An empty script is zero lines, not one empty line - so a blank editor diffs as pure additions. */
    private static List<String> splitLines(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        return List.of(text.split("\n", -1));
    }
}
