package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import java.util.ArrayList;
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
    void theLedgerNeverHandsOutAMutableList() {
        MaterialLedger l = new MaterialLedger();
        List<ItemCount> diamond = List.of(new ItemCount("minecraft:diamond", 1));
        l.recordConsumed(3, diamond);
        l.recordReturned(3);
        assertThrows(UnsupportedOperationException.class, () -> l.consumed(3).clear());
        assertThrows(UnsupportedOperationException.class, () -> l.returned(3).clear());
        assertThrows(UnsupportedOperationException.class, () -> l.consumedView().get(3).clear());
        assertThrows(UnsupportedOperationException.class, () -> l.returnedView().get(3).clear());
        assertEquals(List.of(), l.owedReturn(3), "a caller emptying a stored list must not settle the key twice");
    }

    @Test
    void unrecordTakesBackOnlyWhatIsRecorded() {
        MaterialLedger l = new MaterialLedger();
        List<ItemCount> one = List.of(new ItemCount("minecraft:diamond", 1));
        List<ItemCount> two = List.of(new ItemCount("minecraft:diamond", 2));
        l.recordConsumed(3, one);
        l.recordReturned(3);
        assertThrows(IllegalStateException.class, () -> l.unrecord(MaterialOp.RETURN, 3, two),
                "more than the recorded return would erase the entry and let the charge be returned again");
        assertEquals(one, l.returned(3), "a refused unrecord changes nothing");
        assertThrows(IllegalStateException.class, () -> l.unrecord(MaterialOp.RETURN, 3,
                        List.of(new ItemCount("minecraft:emerald", 1))), "an item that was never recorded");
        assertThrows(IllegalStateException.class, () -> l.unrecord(MaterialOp.RETURN, 9, one),
                "a key without a record");
        assertThrows(IllegalArgumentException.class, () -> l.unrecord(MaterialOp.RETURN, 3, List.of()),
                "an empty list takes nothing back");
        l.unrecord(MaterialOp.RETURN, 3, one);
        assertEquals(one, l.owedReturn(3), "the exact amount makes the return owed again");
        assertFalse(l.isReturned(3));
    }

    @Test
    void aReplayedListCannotBeChangedFromOutside() {
        MaterialLedger l = new MaterialLedger();
        List<ItemCount> dirt = new ArrayList<>(List.of(new ItemCount("minecraft:dirt", 1)));
        l.replay(MaterialOp.YIELD, 5, dirt);
        dirt.clear();
        assertEquals(List.of(new ItemCount("minecraft:dirt", 1)), l.yielded(5), "the caller's list is never stored");
        assertThrows(UnsupportedOperationException.class, () -> l.yielded(5).clear());
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

    @Test
    void theBookListsJobsInIdOrderWhateverTheInsertionOrder() {
        LedgerBook a = new LedgerBook();
        LedgerBook b = new LedgerBook();
        for (String id : List.of("job-9", "job-1", "job-5")) {
            a.of(id);
        }
        for (String id : List.of("job-5", "job-9", "job-1")) {
            b.of(id);
        }
        assertEquals(List.copyOf(a.all().keySet()), List.copyOf(b.all().keySet()),
                "the public view never leaks the insertion order");
        assertEquals(List.of("job-1", "job-5", "job-9"), List.copyOf(a.all().keySet()));
    }
}
