package io.github.khayashi4337.micradrone.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.khayashi4337.micradrone.drone.CornerMarkerNameLedger.MarkerPos;

class CornerMarkerNameLedgerTest {
    private static final MarkerPos A = new MarkerPos(1, 2, 3);
    private static final MarkerPos B = new MarkerPos(10, 20, 30);

    @Test
    void claimingAFreeNameSucceeds() {
        CornerMarkerNameLedger ledger = new CornerMarkerNameLedger();
        assertTrue(ledger.tryClaim("north_field", A));
        assertEquals(A, ledger.ownerOf("north_field").orElseThrow());
    }

    @Test
    void claimingAnAlreadyOwnedNameFromAnotherPositionFails() {
        CornerMarkerNameLedger ledger = new CornerMarkerNameLedger();
        ledger.tryClaim("north_field", A);
        assertFalse(ledger.tryClaim("north_field", B));
        assertEquals(A, ledger.ownerOf("north_field").orElseThrow());
    }

    @Test
    void reclaimingYourOwnNameSucceeds() {
        CornerMarkerNameLedger ledger = new CornerMarkerNameLedger();
        ledger.tryClaim("north_field", A);
        assertTrue(ledger.tryClaim("north_field", A));
    }

    @Test
    void releaseFreesTheNameForSomeoneElse() {
        CornerMarkerNameLedger ledger = new CornerMarkerNameLedger();
        ledger.tryClaim("north_field", A);
        ledger.release("north_field", A);
        assertTrue(ledger.ownerOf("north_field").isEmpty());
        assertTrue(ledger.tryClaim("north_field", B));
    }

    @Test
    void releaseIgnoredWhenTheCallerIsNotTheCurrentOwner() {
        CornerMarkerNameLedger ledger = new CornerMarkerNameLedger();
        ledger.tryClaim("north_field", A);
        ledger.release("north_field", B);
        assertEquals(A, ledger.ownerOf("north_field").orElseThrow());
    }

    @Test
    void roundTripsThroughAMap() {
        CornerMarkerNameLedger ledger = new CornerMarkerNameLedger();
        ledger.tryClaim("north_field", A);
        ledger.tryClaim("south_field", B);
        CornerMarkerNameLedger restored = CornerMarkerNameLedger.fromMap(ledger.asMap());
        assertEquals(Map.of("north_field", A, "south_field", B), restored.asMap());
    }
}
