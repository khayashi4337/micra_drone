package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Start-up recovery in pure Java: the saved files (journal, registry, cursor) are older than the durable log, and the
 * world may be older or newer than both (chunks are saved asynchronously and on unload). recoverAll runs before the
 * first tick; the runtime's start-up checkpoint follows (markDurable).
 */
class JobServiceWalRecoveryTest {
    private static final PlacementManifest M = TestManifests.smallHut();
    private static final String JOB = "job-1";

    static JobService service(WriteAheadLog wal) {
        return new JobService(BudgetConfig.defaults(), new ClaimBook(ClaimBook.DEFAULT_MAX_CLAIMS_PER_OWNER),
                BuildingParts.registry(), true, false, wal);
    }

    private static JournalRecord record(PutItem item) {
        return new JournalRecord(item.ledgerKey(), item.placement().pos(), BlockSpec.AIR, false, item.placement().block(),
                item.ledgerKey());
    }

    /**
     * The disk after a crash: the world holds placements 0..inWorld-1, the saved journal 0..journaled-1, the saved job
     * {@code saved}; the log holds one run with intents for logFrom..logTo-1, ended (with them all written) or cut short.
     */
    private static JobService recovered(FakeJobWorld w, int inWorld, int journaled, ConstructionJob saved, int logFrom, int logTo,
                                        boolean runEnded) {
        JobProgram program = JobProgram.build(M);
        WriteAheadLog.MemorySink sink = new WriteAheadLog.MemorySink();
        sink.durable.add(new WalEntry.RunStart(saved.jobId(), 1));
        List<Integer> written = new ArrayList<>();
        for (int i = logFrom; i < logTo; i++) {
            sink.durable.add(new WalEntry.PlaceIntent(saved.jobId(), 1, record(program.put(i))));
            written.add(program.put(i).ledgerKey());
        }
        if (runEnded) {
            sink.durable.add(new WalEntry.RunEnd(saved.jobId(), 1, written, List.of()));
        }
        JobService s = service(WriteAheadLog.open(sink));
        for (int i = 0; i < inWorld; i++) {
            Placement p = M.placements().get(i);
            w.world.setBlock(p.pos(), p.block());
        }
        Journal journal = new Journal();
        for (int i = 0; i < journaled; i++) {
            JournalRecord rec = record(program.put(i));
            journal.record(rec);
            s.registry("claim-job-1").apply(saved.jobId(), rec);
        }
        add(s, saved, program, journal);
        return s;
    }

    private static void add(JobService s, ConstructionJob saved, JobProgram program, Journal journal) {
        RecoveryDecision d = RecoveryPlanner.decide(saved, new JobLoad.Loaded(
                new JobRecord(saved, M, Map.of(), program, journal, new JobOutcome(), M.worldBounds()), new MaterialLedger()));
        JobRecord r = new JobRecord(d.job(), M, Map.of(), program, journal, new JobOutcome(), M.worldBounds());
        if (d.fromLog()) {
            r.requireRecovery();
        }
        s.add(r);
    }

    /** What the runtime does at start-up: recover every job, then the checkpoint. */
    private static JobService.RecoveryReport startUp(JobService s, FakeJobWorld w) {
        JobService.RecoveryReport report = s.recoverAll(w);
        s.markDurable();
        return report;
    }

    private static ConstructionJob running(String id, int cursor) {
        return JobServiceTest.approved(id, JobServiceTest.A, M, MaterialPolicy.CREATIVE_FREE).withClaimId("claim-job-1")
                .on(JobEvent.ADMITTED).on(JobEvent.START).withCursor(cursor);
    }

    private static long places(FakeJobWorld w) {
        return w.world.log.stream().filter(l -> l.startsWith("place ")).count();
    }

    private static FakeJobWorld world() {
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(JobServiceTest.A);
        return w;
    }

    @Test
    void theWorldAheadOfTheSavedJournalIsTakenFromTheLogNotReportedAsAConflict() {
        FakeJobWorld w = world();
        JobService s = recovered(w, 12, 5, running(JOB, 5), 5, 12, true);
        assertTrue(startUp(s, w).pendingLogs().isEmpty());
        new JobServiceTest.Clock().runUntil(s, w, JOB, JobServiceTest.in(JobState.VERIFIED));
        assertEquals(0, s.status(JOB).orElseThrow().conflicts(), "our own blocks are not someone else's change");
        assertEquals(M.placements().size() - 12, places(w), "logged positions are not placed again");
        assertEquals(M.placements().size(), s.registry("claim-job-1").size(), "a later rollback removes them too");
    }

    @Test
    void aLostWriteBeforeTheSavedCursorIsPlacedAgainByTheReWalkNotByARepairRound() {
        FakeJobWorld w = world();
        // the files were saved after the run (cursor 12), but the world kept only 0..9 of its writes
        JobService s = recovered(w, 10, 5, running(JOB, 12), 5, 12, true);
        startUp(s, w);
        new JobServiceTest.Clock().runUntil(s, w, JOB, JobServiceTest.in(JobState.VERIFIED));
        assertEquals(M.placements().size() - 10, places(w), "10 and 11 again, then the rest");
        assertEquals(0, s.status(JOB).orElseThrow().repairRound(), "the re-walk from the rewound cursor, not L7, put them back");
    }

    @Test
    void aRunCutShortWaitsForTheOwnersAnswerAndNeverRunsOnItsOwn() {
        FakeJobWorld w = world();
        // the run was cut short after its intents for 5..7; 8..11 show the planned blocks too, but no intent names them
        JobService s = recovered(w, 12, 5, running(JOB, 5), 5, 8, false);
        JobService.RecoveryReport report = startUp(s, w);
        assertEquals(List.of(JOB), List.copyOf(report.pendingLogs().keySet()), "its log part is kept for the answer");
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        for (int i = 0; i < JobService.RETRY_INTERVAL_TICKS * 3; i++) {
            c.step(s, w);
        }
        assertEquals(PauseReason.RECOVERY_NEEDED, s.status(JOB).orElseThrow().shownPause());
        assertEquals(0, places(w), "nothing is guessed, nothing is placed");
        assertEquals(ControlResult.OK, s.recover(JOB, JobServiceTest.A, false, RecoveryChoice.ADOPT, null, c.tick, w));
        assertEquals(8, s.record(JOB).orElseThrow().journal().size(), "adopt: the three intents the world shows");
        assertFalse(s.registry("claim-job-1").contains(M.placements().get(8).pos()), "a look-alike without an intent stays the player's");
        c.runUntil(s, w, JOB, JobServiceTest.in(JobState.PAUSED).and(j -> j.pauseReason() == PauseReason.SITE_CHANGED));
        assertEquals(0, places(w));
    }

    @Test
    void discardLeavesTheCutRunsBlocksToThePlayer() {
        FakeJobWorld w = world();
        JobService s = recovered(w, 12, 5, running(JOB, 5), 5, 8, false);
        startUp(s, w);
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        assertEquals(ControlResult.OK, s.recover(JOB, JobServiceTest.A, false, RecoveryChoice.DISCARD, null, c.tick, w));
        assertEquals(5, s.record(JOB).orElseThrow().journal().size());
        c.runUntil(s, w, JOB, JobServiceTest.in(JobState.PAUSED).and(j -> j.pauseReason() == PauseReason.SITE_CHANGED));
        assertEquals(1, s.status(JOB).orElseThrow().conflicts(), "the planks at 5 are somebody else's now");
    }

    @Test
    void aClaimWithAJobWaitingForAnAnswerTakesNoNewWork() {
        FakeJobWorld w = world();
        JobProgram program = JobProgram.build(M);
        WriteAheadLog.MemorySink sink = new WriteAheadLog.MemorySink();
        // a cancelled job of the same claim was cut short inside a run
        sink.durable.add(new WalEntry.RunStart("job-2", 1));
        sink.durable.add(new WalEntry.PlaceIntent("job-2", 1, record(program.put(3))));
        JobService s = service(WriteAheadLog.open(sink));
        Journal journal = new Journal();
        for (Placement p : M.placements()) {
            w.world.setBlock(p.pos(), p.block());
            JournalRecord rec = record(program.put(p.index()));
            journal.record(rec);
            s.registry("claim-job-1").apply(JOB, rec);
        }
        add(s, running(JOB, 25).on(JobEvent.PLACED_ALL).on(JobEvent.CLEAN), program, journal);
        add(s, running("job-2", 3).on(JobEvent.CANCEL), program, new Journal());
        startUp(s, w);
        assertTrue(s.awaitsAnswer("job-2"));
        assertEquals(ControlResult.WRONG_STATE, s.beginVerify(JOB, JobServiceTest.A, false, "job-3", 0L),
                "the claim's registry is not settled until the owner answers");
        assertEquals(ControlResult.OK, s.recover("job-2", JobServiceTest.A, false, RecoveryChoice.DISCARD, null, 0L, w));
        assertEquals(ControlResult.OK, s.beginVerify(JOB, JobServiceTest.A, false, "job-3", 0L));
    }

    @Test
    void positionsBeforeTheCursorThatTheLogCannotExplainStillNeedRecovery() {
        FakeJobWorld w = world();
        JobService s = recovered(w, 8, 5, running(JOB, 10), 5, 8, true);
        startUp(s, w);
        new JobServiceTest.Clock().runUntil(s, w, JOB, JobServiceTest.in(JobState.PAUSED)
                .and(j -> j.pauseReason() == PauseReason.RECOVERY_NEEDED));
        assertEquals(0, places(w), "nothing is placed silently");
        assertEquals(8, s.record(JOB).orElseThrow().journal().size(), "what the log explained was still folded in");
    }

    @Test
    void aRepairCutShortByACrashReDerivesItsLostWriteInTheSameRoundAndChargesNothingTwice() {
        WriteAheadLog.MemorySink sink = new WriteAheadLog.MemorySink();
        JobService before = service(new WriteAheadLog(sink, 0L));
        FakeJobWorld w = world();
        FakeMaterials mats = new FakeMaterials().with("minecraft:cobblestone", 9).with("minecraft:oak_planks", 17);
        w.inventories.put(JobServiceTest.A, mats);
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        JobServiceTest.submit(before, JobServiceTest.approved(JOB, JobServiceTest.A, M, MaterialPolicy.SURVIVAL_CONSUME), M);
        c.runUntil(before, w, JOB, JobServiceTest.in(JobState.VERIFIED));
        IntPos broken = M.placements().get(10).pos();
        w.world.setBlock(broken, BlockSpec.AIR, CellTrait.REPLACEABLE);
        before.beginVerify(JOB, JobServiceTest.A, false, "job-2", c.tick);
        c.runUntil(before, w, "job-2", JobServiceTest.in(JobState.REPAIRING));
        before.tick(new TickInput(c.tick++, JobServiceTest.CALM), w);
        assertEquals(0, mats.count("minecraft:oak_planks"), "the repair round placed the plank and charged it (run saved with its id)");
        JobRecord repair = before.record("job-2").orElseThrow();
        // the crash: the world never saved the repair's write; the files are as at the checkpoint (repair queue not saved)
        w.world.setBlock(broken, BlockSpec.AIR, CellTrait.REPLACEABLE);
        FakeJobWorld back = world();
        back.world.log.clear();
        M.placements().forEach(p -> back.world.setBlock(p.pos(), w.world.blockAt(p.pos())));
        back.inventories.put(JobServiceTest.A, mats.restart());
        JobService after = service(WriteAheadLog.open(sink));
        before.claims().all().forEach(after.claims()::restore);
        after.registry("claim-job-1").putAll(before.registry("claim-job-1").placed());
        JobRecord parent = before.record(JOB).orElseThrow();
        after.add(parent);
        RecoveryDecision d = RecoveryPlanner.decide(repair.job(), new JobLoad.Loaded(repair, new MaterialLedger()));
        JobRecord loaded = new JobRecord(d.job(), M, Map.of(), repair.program(), new Journal(), new JobOutcome(), repair.operatingBox());
        loaded.useBeforesFrom(parent.journal());
        loaded.requireRecovery();
        after.add(loaded);
        LedgerBook fromFiles = after.ledgers();
        fromFiles.put(JOB, before.ledgers().of(JOB));
        startUp(after, back);
        c.runUntil(after, back, "job-2", JobServiceTest.in(JobState.VERIFIED));
        assertEquals(M.placements().get(10).block(), back.world.blockAt(broken), "the lost repair write is placed again");
        assertEquals(0, back.inventories.get(JobServiceTest.A).count("minecraft:oak_planks"), "and not charged a second time");
        assertEquals(1, after.status("job-2").orElseThrow().repairRound(), "the same round: its ledger keys are the charge's");
    }
}
