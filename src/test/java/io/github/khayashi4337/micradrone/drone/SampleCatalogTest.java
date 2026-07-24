package io.github.khayashi4337.micradrone.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SampleCatalogTest {

    @Test
    void withNoBookshelvesExactlyTheThreeStarterEntriesAreUnlocked() {
        long unlocked = 0;
        for (int i = 0; i < SampleCatalog.ALL.size(); i++) {
            if (SampleCatalog.isUnlocked(i, 0)) {
                unlocked++;
            }
        }
        assertEquals(3, unlocked, "help, main, and move_square should need no bookshelves");
        assertTrue(SampleCatalog.isUnlocked(0, 0));
        assertTrue(SampleCatalog.isUnlocked(1, 0));
        assertTrue(SampleCatalog.isUnlocked(2, 0));
    }

    @Test
    void withAFullLibraryEverythingIsUnlocked() {
        for (int i = 0; i < SampleCatalog.ALL.size(); i++) {
            assertTrue(SampleCatalog.isUnlocked(i, SampleCatalog.MAX_BOOKSHELVES), "index " + i);
        }
    }

    @Test
    void unlockFlipsExactlyAtTheRequiredBookshelfCount() {
        for (int i = 0; i < SampleCatalog.ALL.size(); i++) {
            int required = SampleCatalog.ALL.get(i).requiredBookshelves();
            assertTrue(SampleCatalog.isUnlocked(i, required), "index " + i + " at its threshold");
            if (required > 0) {
                assertFalse(SampleCatalog.isUnlocked(i, required - 1), "index " + i + " just below its threshold");
            }
        }
    }

    @Test
    void badIndexesAreNeverUnlocked() {
        assertFalse(SampleCatalog.isUnlocked(-1, SampleCatalog.MAX_BOOKSHELVES));
        assertFalse(SampleCatalog.isUnlocked(SampleCatalog.ALL.size(), SampleCatalog.MAX_BOOKSHELVES));
    }

    @Test
    void catalogIsOrderedEasiestFirstWithSaneCosts() {
        int previousRequired = 0;
        for (SampleCatalog.Sample sample : SampleCatalog.ALL) {
            assertTrue(sample.requiredBookshelves() >= previousRequired,
                    sample.displayName() + " should not need fewer bookshelves than the entry before it");
            assertTrue(sample.requiredBookshelves() <= SampleCatalog.MAX_BOOKSHELVES,
                    sample.displayName() + " must be reachable with a full library");
            assertTrue(sample.lapisCost() >= 1 && sample.lapisCost() <= 3,
                    sample.displayName() + " lapis cost should stay in vanilla's 1..3 range");
            assertFalse(sample.source().isBlank(), sample.displayName() + " must have content");
            previousRequired = sample.requiredBookshelves();
        }
    }

    @Test
    void everyEntryGetsARealDescriptionFromItsLeadingComment() {
        for (SampleCatalog.Sample sample : SampleCatalog.ALL) {
            String description = ScriptFileStore.describeScript(sample.source(), sample.displayName());
            assertFalse(description.equals(sample.displayName()),
                    sample.displayName() + " should describe itself via a leading # comment");
        }
    }
}
