package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import org.junit.jupiter.api.Test;

class JournalRecordTest {
    private static final IntPos P = new IntPos(0, 64, 0);

    @Test
    void aPlacementRecordNeedsItsLedgerKeyToBeTheIndex() {
        assertThrows(IllegalArgumentException.class,
                () -> new JournalRecord(0, P, BlockSpec.AIR, false, BlockSpec.of("minecraft:stone"), 5),
                "an index that is not the ledger key loses the settle chain");
        assertThrows(IllegalArgumentException.class,
                () -> new JournalRecord(0, P, BlockSpec.AIR, false, BlockSpec.of("minecraft:stone"),
                        JournalRecord.NO_LEDGER_KEY), "a non-negative index is a placement's, not a removal's");
    }

    @Test
    void aRemovalRecordIsOnlyTheRestoreIndexAndNoLedgerKeyAndNoCut() {
        JournalRecord removal = new JournalRecord(JournalRecord.restoreIndex(3), P, BlockSpec.of("minecraft:stone"),
                false, BlockSpec.AIR, JournalRecord.NO_LEDGER_KEY);
        assertEquals(-4, removal.placementIndex());
        assertThrows(IllegalArgumentException.class,
                () -> new JournalRecord(-3, P, BlockSpec.AIR, false, BlockSpec.AIR, -3),
                "a negative index with a ledger key is neither a placement nor a removal");
        assertThrows(IllegalArgumentException.class,
                () -> new JournalRecord(JournalRecord.restoreIndex(0), P, BlockSpec.AIR, false, BlockSpec.AIR,
                        JournalRecord.NO_LEDGER_KEY, true), "a removal never cuts ground");
    }

    @Test
    void restoreIndexAcceptsOnlyNonNegativeProgramPositions() {
        assertThrows(IllegalArgumentException.class, () -> JournalRecord.restoreIndex(-1),
                "-1 - (-1) = 0 would collide with a real placement index");
        assertEquals(-1, JournalRecord.restoreIndex(0));
    }
}
