package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The update of a claim's supply settings paired with its save (Task 27e): a change the save could
 * not carry is rolled back in memory, so what is kept never says on while the disk would say off.
 */
class SupplyChangeTest {

    @Test
    void aFailedSaveLeavesTheBookAsItWasAndAnswersFalse() {
        SupplySettingsBook book = new SupplySettingsBook();
        book.allowInventory("claim-1", true);
        boolean ok = SupplyChange.apply(book, "claim-1", new SupplySettings(false, Set.of()),
                () -> false);
        assertFalse(ok);
        assertTrue(book.inventoryAllowed("claim-1"),
                "an off the disk never held must not look stored: memory drops back to the saved on");
    }

    @Test
    void aFailedSaveAlsoRestoresTheExclusions() {
        SupplySettingsBook book = new SupplySettingsBook();
        book.set("claim-1", new SupplySettings(true, Set.of("minecraft:diamond")));
        boolean ok = SupplyChange.apply(book, "claim-1",
                new SupplySettings(false, Set.of("minecraft:dirt")), () -> false);
        assertFalse(ok);
        assertEquals(new SupplySettings(true, Set.of("minecraft:diamond")), book.of("claim-1"));
    }

    @Test
    void aSuccessfulSaveKeepsTheChange() {
        SupplySettingsBook book = new SupplySettingsBook();
        boolean ok = SupplyChange.apply(book, "claim-1",
                new SupplySettings(true, Set.of("minecraft:diamond")), () -> true);
        assertTrue(ok);
        assertTrue(book.inventoryAllowed("claim-1"));
        assertEquals(Set.of("minecraft:diamond"), book.of("claim-1").excludedItems());
    }

    @Test
    void theSaveIsAskedEvenForAnUnchangedValue() {
        SupplySettingsBook book = new SupplySettingsBook();
        boolean[] called = {false};
        SupplyChange.apply(book, "claim-1", new SupplySettings(false, Set.of()), () -> {
            called[0] = true;
            return true;
        });
        assertTrue(called[0], "the save decides whether the entry needs writing, not the caller");
    }
}
