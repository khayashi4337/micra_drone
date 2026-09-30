package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.compile.Conflict;
import io.github.khayashi4337.micradrone.build.compile.ConflictKind;
import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import io.github.khayashi4337.micradrone.build.compile.ObservedBlock;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CodecRoundTripTest {
    /** Every codec must survive the real path: tree, envelope bytes, envelope back, tree back. */
    private static Object throughBytes(String type, Object tree) throws Exception {
        PersistenceEnvelope e = PersistenceEnvelope.fromBytes(new PersistenceEnvelope(type, 1, tree).toBytes());
        return SaveTypes.migrations().payloadOf(e);
    }

    @Test
    void aJobKeepsEveryField() throws Exception {
        ConstructionJob j = ConstructionJob.create("job-1", new UUID(3, 4), "minecraft:overworld", "abc", JobKind.MODIFY, "job-0",
                25, "claim-job-0", MaterialPolicy.SURVIVAL_CONSUME, 77L, List.of("W-UNMODELED:x")).on(JobEvent.ADMITTED)
                .on(JobEvent.START).withCursor(12).paused(PauseReason.MATERIALS_MISSING).withLastError("e").withRepairRound(2);
        assertEquals(j, JobCodec.fromTree(throughBytes(SaveTypes.JOB, JobCodec.toTree(j))));
    }

    @Test
    void theJournalLedgerOutcomeClaimsAndRegistryRoundTrip() throws Exception {
        Journal journal = new Journal();
        journal.record(new JournalRecord(0, new IntPos(1, 2, 3), BlockSpec.of("minecraft:grass_block"), false,
                BlockSpec.of("minecraft:cobblestone"), 0));
        journal.record(new JournalRecord(JournalRecord.restoreIndex(0), new IntPos(1, 3, 3), BlockSpec.of("minecraft:chest"), true,
                BlockSpec.AIR, JournalRecord.NO_LEDGER_KEY));

        journal.record(new JournalRecord(5, new IntPos(1, 4, 3), BlockSpec.of("minecraft:dirt"), false,
                BlockSpec.AIR, 5, true));
        Journal journalBack = JournalCodec.fromTree(throughBytes(SaveTypes.JOURNAL, JournalCodec.toTree(journal)));
        assertEquals(journal.records(), journalBack.records());
        assertEquals(true, journalBack.at(5).orElseThrow().terrainCut(), "the ground owed is settled from the record alone");

        MaterialLedger ledger = new MaterialLedger();
        ledger.recordConsumed(0, List.of(new ItemCount("minecraft:cobblestone", 2)));
        ledger.recordReturned(0, List.of(new ItemCount("minecraft:cobblestone", 1)));
        ledger.recordYield(3, List.of(new ItemCount("minecraft:dirt", 1)));
        ledger.recordReclaimed(3, List.of(new ItemCount("minecraft:dirt", 1)));
        ledger.markAppliedRun(41L);
        MaterialLedger back = LedgerCodec.fromTree(throughBytes(SaveTypes.LEDGER, LedgerCodec.toTree(ledger)));
        assertEquals(ledger.consumedView(), back.consumedView());
        assertEquals(ledger.returnedView(), back.returnedView(), "a partial refund keeps its amount");
        assertEquals(ledger.yieldedView(), back.yieldedView());
        assertEquals(ledger.reclaimedView(), back.reclaimedView());
        assertEquals(41L, back.appliedRun(), "the log's runs up to here are in the ledger already");
        assertEquals(MaterialLedger.NO_RUN, LedgerCodec.fromTree(throughBytes(SaveTypes.LEDGER,
                LedgerCodec.toTree(new MaterialLedger()))).appliedRun());

        JobOutcome o = new JobOutcome();
        o.addConflict(new Conflict(new IntPos(0, 64, 0), BlockSpec.of("minecraft:stone"),
                new ObservedBlock(BlockSpec.of("minecraft:dirt"), false, ""), ConflictKind.PLAYER_MODIFIED));
        o.skip(new SkippedPlacement(4, new IntPos(4, 64, 0), SkippedPlacement.DENIED));
        o.addRestoreConflict(new Conflict(new IntPos(2, 64, 0), BlockSpec.of("minecraft:stone"),
                new ObservedBlock(BlockSpec.of("minecraft:gold_block"), false, ""), ConflictKind.PLAYER_MODIFIED));
        JobOutcome ob = OutcomeCodec.fromTree(throughBytes(SaveTypes.OUTCOME, OutcomeCodec.toTree(o)));
        assertEquals(o.conflicts(), ob.conflicts());
        assertEquals(o.skipped(), ob.skipped());
        assertEquals(true, ob.hasRestoreConflictAt(new IntPos(2, 64, 0)), "a removal conflict still blocks a placement");
        assertEquals(false, ob.hasRestoreConflictAt(new IntPos(0, 64, 0)));

        ClaimBook claims = new ClaimBook(ClaimBook.DEFAULT_MAX_CLAIMS_PER_OWNER);
        claims.reserve("claim-a", new UUID(1, 1), "minecraft:overworld", new Box(0, 0, 0, 5, 5, 5), new Box(0, 0, 0, 5, 9, 5), 3L);
        claims.release("claim-a");
        assertEquals(claims.all(), ClaimCodec.fromTree(throughBytes(SaveTypes.CLAIMS, ClaimCodec.toTree(claims)),
                ClaimBook.DEFAULT_MAX_CLAIMS_PER_OWNER).all());

        PlacedRegistry reg = new PlacedRegistry("claim-a");
        reg.apply("job-1", new JournalRecord(0, new IntPos(1, 2, 3), BlockSpec.AIR, false, BlockSpec.of("minecraft:stone"), 0));
        assertEquals(reg.placed(), RegistryCodec.fromTree(throughBytes(SaveTypes.REGISTRY, RegistryCodec.toTree(reg))).placed());
    }

    @Test
    void aProgramStoresOnlyIndexesAndKeysForItsPuts() throws Exception {
        PlacementManifest m = TestManifests.smallHut();
        JobProgram p = new JobProgram(List.of(new RestoreItem(new IntPos(9, 64, 9), BlockSpec.of("minecraft:stone"),
                Set.of("open"), BlockSpec.AIR, "job-0", 3, true)), JobProgram.repair(m, List.of(2, 5), 1).puts());
        assertEquals(p, ProgramCodec.fromTree(throughBytes(SaveTypes.PROGRAM, ProgramCodec.toTree(p)), m));
    }
}
