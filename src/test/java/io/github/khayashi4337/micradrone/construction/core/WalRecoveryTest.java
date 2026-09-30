package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.List;
import org.junit.jupiter.api.Test;

class WalRecoveryTest {
    private static final String JOB = "job-1";
    private static final BlockSpec PLANKS = BlockSpec.of("minecraft:oak_planks");
    private static final List<ItemCount> ONE_PLANK = List.of(new ItemCount("minecraft:oak_planks", 1));
    private static final IntPos A = new IntPos(0, 64, 0);
    private static final IntPos B = new IntPos(1, 64, 0);
    private static final JobProgram PROGRAM = new JobProgram(List.of(), List.of(
            new PutItem(0, 0, TestManifests.put(0, 64, 0, "minecraft:oak_planks")),
            new PutItem(1, 1, TestManifests.put(1, 64, 0, "minecraft:oak_planks"))));

    private static JournalRecord put(int index, IntPos pos) {
        return new JournalRecord(index, pos, BlockSpec.AIR, false, PLANKS, index);
    }

    private static WalEntry.OpMoves charged(int key, String source) {
        return new WalEntry.OpMoves(new OpKey(JOB, key, MaterialOp.CHARGE), List.of(new Move(source, "minecraft:oak_planks", -1)));
    }

    /** An owner whose saved data holds transaction {@code tx} (what a restart loads). */
    private static FakeMaterials savedAt(long tx) {
        FakeMaterials m = new FakeMaterials();
        m.persist(tx);
        return m.restart();
    }

    private static WalRecovery.Result recover(List<WalEntry> log, Journal journal, PlacedRegistry registry, LedgerBook ledgers,
                                              FakeWorld w, FakeMaterials m, WalRecovery.Resolution r) {
        return WalRecovery.recover(JOB, log, PROGRAM, journal, registry, ledgers, w, m.durableTx(), r);
    }

    @Test
    void aFinishedRunCountsOnlyWhereTheWorldHoldsExactlyThePlannedBlock() {
        FakeWorld w = new FakeWorld();
        w.setBlock(A, PLANKS);
        Journal journal = new Journal();
        PlacedRegistry registry = new PlacedRegistry("claim-1");
        List<WalEntry> log = List.of(new WalEntry.RunStart(JOB, 1), new WalEntry.PlaceIntent(JOB, 1, put(0, A)),
                new WalEntry.PlaceIntent(JOB, 1, put(1, B)), new WalEntry.RunEnd(JOB, 1, List.of(0, 1), List.of()));
        WalRecovery.Result r = recover(log, journal, registry, new LedgerBook(), w, savedAt(0), WalRecovery.Resolution.NONE);
        assertFalse(r.ambiguous());
        assertEquals(1, r.adopted());
        assertTrue(registry.contains(A));
        assertFalse(registry.contains(B), "B's write was lost with the unsaved world");
        assertEquals(0, r.rewindTo(), "the re-walk starts at the run's first step and places B again");
    }

    @Test
    void aRunCutShortIsLeftToTheOwnerAndChangesNothingUntilAnswered() {
        FakeWorld w = new FakeWorld();
        w.setBlock(A, PLANKS);
        List<WalEntry> log = List.of(new WalEntry.RunStart(JOB, 1), new WalEntry.PlaceIntent(JOB, 1, put(0, A)),
                new WalEntry.MaterialIntent(JOB, 1, new OpKey(JOB, 0, MaterialOp.CHARGE), ONE_PLANK));
        Journal journal = new Journal();
        LedgerBook ledgers = new LedgerBook();
        WalRecovery.Result r = recover(log, journal, new PlacedRegistry("claim-1"), ledgers, w, savedAt(0),
                WalRecovery.Resolution.NONE);
        assertTrue(r.ambiguous());
        assertEquals(List.of(WalRecovery.REASON_CUT_SHORT + ":1"), r.reasons());
        assertEquals(0, journal.size(), "nothing is guessed");
        PlacedRegistry adopted = new PlacedRegistry("claim-1");
        recover(log, new Journal(), adopted, ledgers, w, savedAt(0), WalRecovery.Resolution.ADOPT);
        assertTrue(adopted.contains(A));
        assertEquals(ONE_PLANK, ledgers.of(JOB).consumed(0), "adopt: the operation happened");
        PlacedRegistry discarded = new PlacedRegistry("claim-1");
        LedgerBook fresh = new LedgerBook();
        recover(log, new Journal(), discarded, fresh, w, savedAt(0), WalRecovery.Resolution.DISCARD);
        assertFalse(discarded.contains(A), "discard: the planks there are somebody else's");
        assertFalse(fresh.of(JOB).isConsumed(0));
    }

    @Test
    void aSupplyChestTakeCannotBeProvenAndWaitsForTheOwner() {
        LedgerBook ledgers = new LedgerBook();
        ledgers.of(JOB).recordConsumed(0, ONE_PLANK);
        ledgers.of(JOB).markAppliedRun(1);
        List<WalEntry> log = List.of(new WalEntry.RunStart(JOB, 1),
                new WalEntry.RunEnd(JOB, 1, List.of(), List.of(charged(0, FakeMaterials.CHEST))));
        assertTrue(recover(log, new Journal(), new PlacedRegistry("claim-1"), ledgers, new FakeWorld(), savedAt(0),
                WalRecovery.Resolution.NONE).ambiguous());
        assertEquals(ONE_PLANK, ledgers.of(JOB).consumed(0), "unchanged while waiting");
        recover(log, new Journal(), new PlacedRegistry("claim-1"), ledgers, new FakeWorld(), savedAt(0),
                WalRecovery.Resolution.DISCARD);
        assertFalse(ledgers.of(JOB).isConsumed(0), "discard: the chest was not saved with the take; it is charged again");
    }

    @Test
    void theInventoryPartCountsByTheSavedTransactionIdNeverByCounts() {
        LedgerBook ledgers = new LedgerBook();
        List<WalEntry> log = List.of(new WalEntry.RunEnd(JOB, 1, List.of(), List.of(charged(0, MaterialPort.INVENTORY))),
                new WalEntry.RunEnd(JOB, 2, List.of(), List.of(charged(1, MaterialPort.INVENTORY))));
        // the owner picked things up and dropped others since: counts say nothing, the id says run 1 was saved
        FakeMaterials owner = savedAt(1);
        owner.ownerChanges("minecraft:oak_planks", 7);
        WalRecovery.Result r = recover(log, new Journal(), new PlacedRegistry("claim-1"), ledgers, new FakeWorld(), owner,
                WalRecovery.Resolution.NONE);
        assertEquals(ONE_PLANK, ledgers.of(JOB).consumed(0), "run 1's take is in the saved inventory");
        assertFalse(ledgers.of(JOB).isConsumed(1), "run 2's take never reached the disk");
        assertEquals(1, r.lostMoves());
    }

    @Test
    void runsCoveredByADurablePointAreLeftAsTheFilesHaveThem() {
        FakeWorld w = new FakeWorld();
        Journal journal = new Journal();
        // run 1 was cut short too, but a flushed save came after it: it is on the disk as the files have it
        List<WalEntry> log = List.of(new WalEntry.PlaceIntent(JOB, 1, put(0, A)), new WalEntry.DurablePoint(1),
                new WalEntry.PlaceIntent(JOB, 2, put(1, B)));
        WalRecovery.Result r = recover(log, journal, new PlacedRegistry("claim-1"), new LedgerBook(), w, savedAt(0),
                WalRecovery.Resolution.NONE);
        assertEquals(List.of(WalRecovery.REASON_CUT_SHORT + ":2"), r.reasons(), "only the run after the durable point counts");
        assertEquals(List.of(new WalEntry.PlaceIntent(JOB, 2, put(1, B))), WalRecovery.pendingPart(JOB, log));
    }

    @Test
    void aDropBeforeTheDurablePointIsReportedAsPossiblyLost() {
        List<WalEntry> log = List.of(new WalEntry.RunStart(JOB, 1), new WalEntry.DropIntent(JOB, 1, A),
                new WalEntry.RunEnd(JOB, 1, List.of(), List.of()));
        assertEquals(List.of(A), recover(log, new Journal(), new PlacedRegistry("claim-1"), new LedgerBook(), new FakeWorld(),
                savedAt(0), WalRecovery.Resolution.NONE).possiblyLostDrops());
    }

    @Test
    void aDurablePointDropsTheLogButKeepsTheRunNumbers() {
        WriteAheadLog.MemorySink sink = new WriteAheadLog.MemorySink();
        WriteAheadLog wal = new WriteAheadLog(sink, 0L);
        for (int i = 0; i < 3; i++) {
            long run = wal.newRun();
            wal.append(new WalEntry.RunStart(JOB, run));
            wal.append(new WalEntry.RunEnd(JOB, run, List.of(), List.of()));
        }
        wal.flush();
        wal.markDurable(wal.lastRun());
        assertEquals(List.of(new WalEntry.DurablePoint(3)), sink.durable, "the log holds only what came after");
        WriteAheadLog reopened = WriteAheadLog.open(sink);
        assertEquals(3, reopened.durableUpTo());
        assertEquals(4, reopened.newRun(), "a reused run number would look already applied to a ledger");
    }

    @Test
    void aWriteTheSameJobBuiltOverIsTakenWithTheOneTheWorldShows() {
        // the ground cut (key 0) and then the foundation on it (key 5), both after the durable point
        BlockSpec cobble = BlockSpec.of("minecraft:cobblestone");
        JournalRecord cut = new JournalRecord(0, A, BlockSpec.of("minecraft:grass_block"), false, BlockSpec.AIR, 0, true);
        JournalRecord foundation = new JournalRecord(5, A, BlockSpec.AIR, false, cobble, 5);
        FakeWorld w = new FakeWorld();
        w.setBlock(A, cobble);
        List<WalEntry> log = List.of(new WalEntry.PlaceIntent(JOB, 1, cut), new WalEntry.RunEnd(JOB, 1, List.of(0), List.of()),
                new WalEntry.PlaceIntent(JOB, 2, foundation), new WalEntry.RunEnd(JOB, 2, List.of(5), List.of()));
        Journal journal = new Journal();
        PlacedRegistry registry = new PlacedRegistry("claim-1");
        WalRecovery.recover(JOB, log, PROGRAM, journal, registry, new LedgerBook(), w, 0L, WalRecovery.Resolution.NONE);
        assertTrue(journal.at(0).isPresent(), "a chunk is saved as one snapshot: the foundation there means the cut happened");
        assertEquals(List.of(0), registry.at(A).orElseThrow().earlierKeys());
    }
}
