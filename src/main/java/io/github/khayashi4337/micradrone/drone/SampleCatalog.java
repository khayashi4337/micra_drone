package io.github.khayashi4337.micradrone.drone;

import java.util.List;

/**
 * The enchanting table's scroll catalog (issue #8): what a blank script scroll can be inscribed
 * with, ordered easy to hard. Availability is gated by the number of valid bookshelves around the
 * table - the same 0..15 count vanilla enchanting uses - so growing the library literally unlocks
 * more advanced knowledge, and each inscription costs lapis lazuli like a vanilla enchant.
 * Minecraft-free so the unlock rules are unit-testable; the glue that counts bookshelves and
 * consumes lapis lives with the payload handler.
 */
public final class SampleCatalog {
    /** Vanilla's maximum effective bookshelf count around an enchanting table. */
    public static final int MAX_BOOKSHELVES = 15;

    /**
     * One inscribable scroll: {@code displayName} becomes the scroll's custom name,
     * {@code source} its pages, unlocked once {@code requiredBookshelves} valid bookshelves
     * surround the table, at a price of {@code lapisCost} lapis lazuli.
     */
    public record Sample(String displayName, String source, int requiredBookshelves, int lapisCost) {
    }

    /** All inscribable scrolls, easiest first; indexes into this list travel in EnchantScrollPayload. */
    public static final List<Sample> ALL = List.of(
            new Sample("ヘルプ(コマンド一覧)", CommandsHelpDoc.CONTENT, 0, 1),
            new Sample("main", SampleScripts.MAIN, 0, 1),
            new Sample("move_square", SampleScripts.MOVE_SQUARE, 0, 1),
            new Sample("till_and_plant", SampleScripts.TILL_AND_PLANT, 3, 1),
            new Sample("harvest_when_ready", SampleScripts.HARVEST_WHEN_READY, 6, 2),
            new Sample("carrot_farm", SampleScripts.CARROT_FARM, 9, 2),
            new Sample("pumpkin_smart_harvest", SampleScripts.PUMPKIN_SMART_HARVEST, MAX_BOOKSHELVES, 3));

    /** Whether the sample at {@code index} is available with {@code bookshelfCount} bookshelves; false for bad indexes. */
    public static boolean isUnlocked(int index, int bookshelfCount) {
        if (index < 0 || index >= ALL.size()) {
            return false;
        }
        return ALL.get(index).requiredBookshelves() <= bookshelfCount;
    }

    private SampleCatalog() {
    }
}
