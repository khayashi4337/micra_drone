package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The on-disk shape of a claim's supply settings (Task 27a/27b): claims/<claimId>/supply.bin. */
class SupplyCodecTest {
    /** The real path: tree, sealed envelope bytes, envelope back, migrated payload. */
    private static SupplySettings roundTrip(SupplySettings s) throws Exception {
        PersistenceEnvelope e = PersistenceEnvelope.fromBytes(
                new PersistenceEnvelope(SaveTypes.SUPPLY, SaveTypes.currentVersions().get(SaveTypes.SUPPLY),
                        SupplyCodec.toTree(s)).toBytes());
        return SupplyCodec.fromTree(SaveTypes.migrations().payloadOf(e));
    }

    @Test
    void supplySettingsRoundTripThroughTheEnvelope() throws Exception {
        assertEquals(new SupplySettings(true, Set.of()),
                roundTrip(new SupplySettings(true, Set.of())));
        assertEquals(new SupplySettings(false, Set.of()),
                roundTrip(new SupplySettings(false, Set.of())),
                "an explicit off survives too, so a removed permission cannot silently come back");
    }

    @Test
    void theExcludedItemsRoundTripToo() throws Exception {
        assertEquals(new SupplySettings(true, Set.of("minecraft:diamond", "minecraft:emerald")),
                roundTrip(new SupplySettings(true, Set.of("minecraft:emerald", "minecraft:diamond"))));
    }

    @Test
    void anOldSupplyFileWithoutExcludedItemsDecodesAsEmpty() {
        // the Task 27a shape: only the flag - a missing excludedItems is the empty set, never an error
        assertEquals(new SupplySettings(true, Set.of()),
                SupplyCodec.fromTree(Map.of("inventoryAllowed", true)));
    }

    @Test
    void theSupplyTypeIsSealedAtVersionOne() {
        assertEquals(1, SaveTypes.currentVersions().get(SaveTypes.SUPPLY).intValue());
    }

    @Test
    void aMissingKeyDoesNotDecode() {
        assertThrows(IllegalArgumentException.class,
                () -> SupplyCodec.fromTree(java.util.Map.of()),
                "a supply file without the flag is broken, not silently on");
    }

    @Test
    void aWronglyTypedExcludedItemsDoesNotDecode() {
        assertThrows(IllegalArgumentException.class,
                () -> SupplyCodec.fromTree(Map.of("inventoryAllowed", true,
                        "excludedItems", "minecraft:diamond")),
                "a string where the list should be is a broken file, not a guessed one");
        assertThrows(IllegalArgumentException.class,
                () -> SupplyCodec.fromTree(Map.of("inventoryAllowed", true,
                        "excludedItems", List.of(5))),
                "a non-string entry cannot be an item id");
    }

    @Test
    void anExcludedListLongerThanTheCapDoesNotDecode() {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= SupplySettings.MAX_EXCLUDED_ITEMS; i++) {
            tooMany.add("minecraft:item" + i);
        }
        assertThrows(IllegalArgumentException.class,
                () -> SupplyCodec.fromTree(Map.of("inventoryAllowed", false, "excludedItems", tooMany)),
                "the saved set obeys the same cap the wire does");
    }
}
