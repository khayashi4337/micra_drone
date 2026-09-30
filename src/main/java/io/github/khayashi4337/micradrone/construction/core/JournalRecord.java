package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.Objects;

/**
 * One block the job changed: the block before (the UndoEntry of design 01, section 8), whether a block entity stood
 * there, the block now, the ledger key the material was charged under ({@link #NO_LEDGER_KEY} for a removal), and
 * whether the placement cut natural ground (so the ground owed to the owner can be settled from the record alone).
 */
public record JournalRecord(int placementIndex, IntPos pos, BlockSpec before, boolean beforeHadBlockEntity, BlockSpec placed,
                            int ledgerKey, boolean terrainCut) {
    public static final int NO_LEDGER_KEY = -1;

    /**
     * The journal index of a removal: negative, so a MODIFY job's removals (program positions) never collide with its
     * additions (new-manifest indexes) in the one journal.
     */
    public static int restoreIndex(int programIndex) {
        return -1 - programIndex;
    }

    public JournalRecord {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(placed, "placed");
    }

    /** A record of a placement that cut no ground, or of a removal. */
    public JournalRecord(int placementIndex, IntPos pos, BlockSpec before, boolean beforeHadBlockEntity, BlockSpec placed,
                         int ledgerKey) {
        this(placementIndex, pos, before, beforeHadBlockEntity, placed, ledgerKey, false);
    }

    public UndoEntry undo() {
        return new UndoEntry(pos, before);
    }
}
