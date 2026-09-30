package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import java.util.List;
import org.junit.jupiter.api.Test;

class MaterialLedgerTest {
    private static final List<ItemCount> PLANK = List.of(new ItemCount("minecraft:oak_planks", 1));

    @Test
    void aChargeIsReturnedOnceAtMost() {
        MaterialLedger l = new MaterialLedger();
        assertFalse(l.isConsumed(3));
        assertThrows(IllegalStateException.class, () -> l.recordReturned(3), "nothing to return before it was charged");
        l.recordConsumed(3, PLANK);
        assertTrue(l.isConsumed(3));
        assertEquals(PLANK, l.consumed(3));
        assertEquals(PLANK, l.owedReturn(3));
        l.recordReturned(3);
        assertTrue(l.isReturned(3));
        assertThrows(IllegalStateException.class, () -> l.recordReturned(3), "never return twice");
        assertThrows(IllegalStateException.class, () -> l.recordReturned(3, PLANK), "not even piece by piece");
        assertEquals(List.of(), l.consumed(4));
    }

    @Test
    void amountsAddUpSoAPartlyDurableChargeCanBeCompleted() {
        MaterialLedger l = new MaterialLedger();
        List<ItemCount> two = List.of(new ItemCount("minecraft:oak_slab", 2));
        List<ItemCount> one = List.of(new ItemCount("minecraft:oak_slab", 1));
        l.recordConsumed(5, one);
        assertEquals(one, ItemCount.minus(two, l.consumed(5)), "one slab of the double slab is still owed");
        l.recordConsumed(5, one);
        assertEquals(two, l.consumed(5));
        l.unrecord(MaterialOp.CHARGE, 5, one);
        assertEquals(one, l.consumed(5), "recovery takes back only the part that never became durable");
        l.unrecord(MaterialOp.CHARGE, 5, one);
        assertFalse(l.isConsumed(5));
    }

    @Test
    void cutGroundIsGivenAndTakenBackOnce() {
        MaterialLedger l = new MaterialLedger();
        List<ItemCount> dirt = List.of(new ItemCount("minecraft:dirt", 1));
        l.recordYield(7, dirt);
        assertTrue(l.isYielded(7));
        assertEquals(dirt, l.owedReclaim(7));
        l.recordReclaimed(7);
        assertTrue(l.isReclaimed(7));
        assertThrows(IllegalStateException.class, () -> l.recordReclaimed(7));
        assertThrows(IllegalArgumentException.class, () -> l.recordConsumed(8, List.of()), "an empty charge is a bug");
    }

    @Test
    void theAppliedRunOnlyMovesForward() {
        MaterialLedger l = new MaterialLedger();
        assertEquals(MaterialLedger.NO_RUN, l.appliedRun());
        l.markAppliedRun(4);
        l.markAppliedRun(2);
        assertEquals(4, l.appliedRun());
    }

    @Test
    void theBookHandsOutOneLedgerPerJob() {
        LedgerBook book = new LedgerBook();
        assertTrue(book.find("job-1").isEmpty());
        MaterialLedger a = book.of("job-1");
        assertTrue(a == book.of("job-1"));
        assertEquals(1, book.all().size());
    }
}
