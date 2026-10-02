package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** The on-disk shape of a claim's supply settings (Task 27a): claims/<claimId>/supply.bin. */
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
        assertEquals(new SupplySettings(true), roundTrip(new SupplySettings(true)));
        assertEquals(new SupplySettings(false), roundTrip(new SupplySettings(false)),
                "an explicit off survives too, so a removed permission cannot silently come back");
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
}
