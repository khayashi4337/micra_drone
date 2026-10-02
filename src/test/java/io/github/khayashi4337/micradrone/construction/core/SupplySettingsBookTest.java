package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The per-claim inventory switch (Task 27a): off by default, set per claim, dropped on release. */
class SupplySettingsBookTest {
    @Test
    void aClaimThatWasNeverSetDefaultsToDisallowed() {
        assertFalse(new SupplySettingsBook().inventoryAllowed("claim-1"),
                "the safe default: a child's things are never spent without an explicit yes");
        assertEquals(SupplySettings.DEFAULT, new SupplySettingsBook().of("claim-1"));
    }

    @Test
    void allowInventoryRoundTrips() {
        SupplySettingsBook book = new SupplySettingsBook();
        book.allowInventory("claim-1", true);
        assertTrue(book.inventoryAllowed("claim-1"));
        book.allowInventory("claim-1", false);
        assertFalse(book.inventoryAllowed("claim-1"), "turning it back off is an ordinary change");
    }

    @Test
    void claimsAreIndependent() {
        SupplySettingsBook book = new SupplySettingsBook();
        book.allowInventory("claim-a", true);
        assertTrue(book.inventoryAllowed("claim-a"));
        assertFalse(book.inventoryAllowed("claim-b"), "one claim's yes is never another claim's");
    }

    @Test
    void removeDropsTheClaimBackToTheDefault() {
        SupplySettingsBook book = new SupplySettingsBook();
        book.allowInventory("claim-1", true);
        book.remove("claim-1");
        assertFalse(book.inventoryAllowed("claim-1"), "a released claim keeps nothing (not even a yes)");
    }
}
