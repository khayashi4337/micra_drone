package io.github.khayashi4337.micradrone.drone;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure text logic for the script scroll item (GitHub issue #1: a portable, freely-rewritable
 * carrier for a script, so players can hand/trade one instead of only editing local files). Kept
 * Minecraft-free - see {@code ScriptFileStore} for the same rationale - so it can be unit tested
 * without touching {@code WritableBookContent}. The scroll's pages are concatenated with no
 * separator (matching how the vanilla book-and-quill GUI itself splits/stores text: a page break
 * is not a logical newline), so splitting and joining round-trip losslessly.
 */
public final class ScriptScrollContent {
    /** Fixed file name a scroll's content is written to in the target controller's script folder. */
    public static final String SCROLL_SCRIPT_NAME = "scroll.mdrone";

    private ScriptScrollContent() {
    }

    /** Concatenates a scroll's pages back into the single script source they represent. */
    public static String joinPages(List<String> pages) {
        return String.join("", pages);
    }

    /** True if a scroll's pages amount to no real content (no pages, or only whitespace/newlines). */
    public static boolean isBlank(List<String> pages) {
        return joinPages(pages).isBlank();
    }

    /**
     * Splits {@code source} into chunks of at most {@code maxPageLength} characters each, in order,
     * with no inserted separators - the inverse of {@link #joinPages}. An empty source yields no
     * pages at all (an empty scroll), matching {@link #isBlank}'s definition of blank.
     */
    public static List<String> splitIntoPages(String source, int maxPageLength) {
        List<String> pages = new ArrayList<>();
        int start = 0;
        while (start < source.length()) {
            int end = Math.min(start + maxPageLength, source.length());
            pages.add(source.substring(start, end));
            start = end;
        }
        return pages;
    }
}
