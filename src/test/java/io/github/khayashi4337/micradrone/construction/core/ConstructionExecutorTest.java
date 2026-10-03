package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.ReplacePolicy;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.PlacerId;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConstructionExecutorTest {
    private static final int PLENTY = 10_000;

    static ConstructionJob running(MaterialPolicy policy, int total) {
        return ConstructionJob.create("job-1", ConstructionJobTest.OWNER, TestManifests.DIM, "h", JobKind.BUILD, null, total,
                "claim-job-1", policy, 0L, List.of()).on(JobEvent.ADMITTED).on(JobEvent.START);
    }

    static ExecutionContext ctx(ConstructionJob job, JobProgram program, FakeWorld world, MaterialPort materials,
                                Journal journal, LedgerBook ledgers, PlacedRegistry registry, JobOutcome outcome, boolean skip) {
        return new ExecutionContext(job, program, world, materials, journal, ledgers, registry, outcome, skip,
                WriteAheadLog.inMemory());
    }

    private static ExecutionContext fresh(PlacementManifest m, FakeWorld world, MaterialPolicy policy, MaterialPort mats) {
        return ctx(running(policy, m.placements().size()), JobProgram.build(m), world, mats, new Journal(), new LedgerBook(),
                new PlacedRegistry("claim-job-1"), new JobOutcome(), false);
    }

    @Test
    void theHutGoesUpInOrderAndEveryBlockIsJournaled() {
        PlacementManifest m = TestManifests.smallHut();
        FakeWorld w = new FakeWorld();
        ExecutionContext c = fresh(m, w, MaterialPolicy.CREATIVE_FREE, MaterialPort.FREE);
        StepReport r = ConstructionExecutor.run(c, 0, PLENTY);
        assertNull(r.pause());
        assertEquals(25, r.cursor());
        assertEquals(25, r.touched().size());
        assertEquals(25, c.journal().size());
        assertEquals(25, c.registry().size());
        for (Placement p : m.placements()) {
            assertEquals(p.block(), w.blockAt(p.pos()));
        }
        assertEquals("place 0,63,0 minecraft:cobblestone", w.log.get(0), "construction order is the manifest order");
    }

    @Test
    void theAllowanceBoundsOneCall() {
        ExecutionContext c = fresh(TestManifests.smallHut(), new FakeWorld(), MaterialPolicy.CREATIVE_FREE, MaterialPort.FREE);
        assertEquals(4, ConstructionExecutor.run(c, 0, 4).cursor());
        assertEquals(9, ConstructionExecutor.run(c, 4, 5).cursor());
    }

    @Test
    void aJournalAheadOfTheCursorResumesWithoutChargingTwice() {
        PlacementManifest m = TestManifests.smallHut();
        FakeWorld w = new FakeWorld();
        FakeMaterials mats = new FakeMaterials().with("minecraft:cobblestone", 9).with("minecraft:oak_planks", 16);
        ExecutionContext c = fresh(m, w, MaterialPolicy.SURVIVAL_CONSUME, mats);
        assertEquals(10, ConstructionExecutor.run(c, 0, 10).cursor());
        int writes = w.log.size();
        StepReport again = ConstructionExecutor.run(c, 5, PLENTY);
        assertEquals(25, again.cursor());
        assertEquals(writes + 15, w.log.size(), "indexes 5..9 were already in place: no second write");
        assertEquals(0, mats.count("minecraft:cobblestone"));
        assertEquals(0, mats.count("minecraft:oak_planks"), "25 blocks, 25 items: nothing charged twice");
    }

    @Test
    void pausesWhenAnUnbuiltPositionChanged() {
        PlacementManifest m = TestManifests.smallHut();
        FakeWorld w = new FakeWorld();
        IntPos third = m.placements().get(3).pos();
        w.setBlock(third, BlockSpec.of("minecraft:stone_bricks"));
        ExecutionContext c = fresh(m, w, MaterialPolicy.CREATIVE_FREE, MaterialPort.FREE);
        StepReport r = ConstructionExecutor.run(c, 0, PLENTY);
        assertEquals(PauseReason.SITE_CHANGED, r.pause());
        assertEquals(3, r.cursor(), "stopped before the changed position, nothing overwritten");
        assertEquals("minecraft:stone_bricks", w.blockAt(third).blockId());
        assertEquals(1, r.conflicts().size());
        ExecutionContext skip = ctx(c.job(), c.program(), w, MaterialPort.FREE, c.journal(), c.ledgers(), c.registry(),
                c.outcome(), true);
        StepReport done = ConstructionExecutor.run(skip, 3, PLENTY);
        assertNull(done.pause());
        assertEquals(25, done.cursor());
        assertEquals("minecraft:stone_bricks", w.blockAt(third).blockId(), "skipping never overwrites");
        assertEquals(1, c.outcome().conflicts().size(), "the same position is reported once");
    }

    @Test
    void anUnloadedChunkPausesWithoutWriting() {
        PlacementManifest m = TestManifests.smallHut();
        FakeWorld w = new FakeWorld();
        w.unload(m.placements().get(2).pos());
        StepReport r = ConstructionExecutor.run(fresh(m, w, MaterialPolicy.CREATIVE_FREE, MaterialPort.FREE), 0, PLENTY);
        assertEquals(PauseReason.CHUNK_UNLOADED, r.pause());
        assertEquals(2, r.cursor());
    }

    @Test
    void survivalChargesTheDoorOnceAndPausesWhenShort() {
        List<Placement> ps = List.of(
                TestManifests.put(0, 64, 0, "minecraft:oak_door", "half", "lower"),
                TestManifests.put(0, 65, 0, "minecraft:oak_door", "half", "upper"),
                TestManifests.put(1, 64, 0, "minecraft:oak_planks"));
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 2, 70, 1), ps);
        FakeWorld w = new FakeWorld();
        FakeMaterials mats = new FakeMaterials().with("minecraft:oak_door", 1);
        ExecutionContext c = fresh(m, w, MaterialPolicy.SURVIVAL_CONSUME, mats);
        StepReport r = ConstructionExecutor.run(c, 0, PLENTY);
        assertEquals(PauseReason.MATERIALS_MISSING, r.pause());
        assertEquals(2, r.cursor());
        assertEquals(List.of(new ItemCount("minecraft:oak_planks", 1)), r.shortage());
        assertEquals(0, mats.count("minecraft:oak_door"), "one door item for both halves");
        assertEquals(BlockSpec.AIR, w.blockAt(new IntPos(1, 64, 0)), "nothing is placed without its material");
        mats.with("minecraft:oak_planks", 1);
        StepReport done = ConstructionExecutor.run(c, r.cursor(), PLENTY);
        assertNull(done.pause());
        assertEquals(0, mats.count("minecraft:oak_planks"));
        assertEquals(List.of(0, 2), List.copyOf(c.ledgers().of("job-1").consumedView().keySet()));
    }

    @Test
    void aDeniedPlacementIsSkippedAndNotCharged() {
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 2, 70, 1),
                List.of(TestManifests.put(0, 64, 0, "minecraft:oak_planks")));
        FakeWorld w = new FakeWorld();
        w.deny(new IntPos(0, 64, 0));
        FakeMaterials mats = new FakeMaterials().with("minecraft:oak_planks", 1);
        ExecutionContext c = fresh(m, w, MaterialPolicy.SURVIVAL_CONSUME, mats);
        StepReport r = ConstructionExecutor.run(c, 0, PLENTY);
        assertNull(r.pause());
        assertEquals(1, r.cursor());
        assertEquals(1, mats.count("minecraft:oak_planks"));
        assertEquals(java.util.Set.of(0), c.outcome().deniedIndexes());
        assertEquals(0, c.journal().size());
    }

    @Test
    void cuttingGroundGivesItToTheOwnerOnce() {
        Placement sunk = new Placement(0, new IntPos(0, 63, 0), BlockSpec.of("minecraft:cobblestone"), Map.of(), "found",
                BuildPhase.STRUCTURE, PlacerId.SIMPLE, VerifyMode.EXACT, ReplacePolicy.TERRAFORM, null);
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 1, 70, 1), List.of(sunk));
        FakeWorld w = new FakeWorld();
        w.setBlock(new IntPos(0, 63, 0), BlockSpec.of("minecraft:grass_block"), CellTrait.TERRAFORMABLE);
        FakeMaterials mats = new FakeMaterials().with("minecraft:cobblestone", 1);
        ExecutionContext c = fresh(m, w, MaterialPolicy.SURVIVAL_CONSUME, mats);
        ConstructionExecutor.run(c, 0, PLENTY);
        assertEquals(1, mats.count("minecraft:dirt"), "the cut grass block is handed over as dirt");
        assertEquals(0, mats.count("minecraft:cobblestone"));
        assertTrue(c.ledgers().of("job-1").isYielded(0));
        assertEquals("minecraft:grass_block", c.journal().at(0).orElseThrow().before().blockId());
    }

    @Test
    void aBlockAlreadyThereIsStillPaidForWhenTheLedgerLostTheCharge() {
        PlacementManifest m = TestManifests.smallHut();
        FakeWorld w = new FakeWorld();
        FakeMaterials mats = new FakeMaterials().with("minecraft:cobblestone", 18).with("minecraft:oak_planks", 32);
        ExecutionContext first = fresh(m, w, MaterialPolicy.SURVIVAL_CONSUME, mats);
        ConstructionExecutor.run(first, 0, PLENTY);
        assertEquals(9, mats.count("minecraft:cobblestone"));
        long placed = w.log.stream().filter(l -> l.startsWith("place ")).count();
        // a crash lost the charges but not the blocks: the journal and registry survived, the ledger did not
        ExecutionContext after = ctx(first.job(), first.program(), w, mats, first.journal(), new LedgerBook(), first.registry(),
                new JobOutcome(), false);
        StepReport r = ConstructionExecutor.run(after, 0, PLENTY);
        assertNull(r.pause());
        assertEquals(placed, w.log.stream().filter(l -> l.startsWith("place ")).count(), "no block is placed twice");
        assertEquals(0, mats.count("minecraft:cobblestone"), "the blocks that are there are paid for once more, exactly");
        assertEquals(0, mats.count("minecraft:oak_planks"));
        assertEquals(25, after.ledgers().of("job-1").consumedView().size());
        StepReport again = ConstructionExecutor.run(after, 0, PLENTY);
        assertNull(again.pause(), "nothing is owed any more, so nothing is missing");
        assertEquals(0, mats.count("minecraft:oak_planks"), "and never a third time");
    }

    @Test
    void everyRunLogsItsIntentsBeforeAnyChangeAndItsEndBeforeTheInventoryIsSavedWithItsId() {
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 2, 70, 1),
                List.of(TestManifests.put(0, 64, 0, "minecraft:oak_planks")));
        FakeWorld w = new FakeWorld();
        FakeMaterials mats = new FakeMaterials().with("minecraft:oak_planks", 1);
        ExecutionContext c = fresh(m, w, MaterialPolicy.SURVIVAL_CONSUME, mats);
        WriteAheadLog.MemorySink sink = (WriteAheadLog.MemorySink) c.wal().sink();
        List<String> order = new java.util.ArrayList<>();
        sink.hook = () -> order.add("log" + sink.durable.size());
        mats.hook = () -> order.add("items");
        w.afterWrite = (pos, block) -> order.add("world");
        c.wal().setBarrier(order::add);
        ConstructionExecutor.run(c, 0, PLENTY);
        assertEquals(List.of("log0", "log3", WriteAheadLog.Barrier.AFTER_INTENTS, "world", "items", "items",
                WriteAheadLog.Barrier.AFTER_WRITES, "log3", "log4", WriteAheadLog.Barrier.AFTER_RUN_END, "items", "items",
                WriteAheadLog.Barrier.AFTER_PLAYER_SAVE), order,
                "intents, then the world and the take, then the run's end, then the inventory saved with the run's id");
        assertTrue(sink.durable.get(0) instanceof WalEntry.RunStart);
        assertTrue(sink.durable.get(1) instanceof WalEntry.PlaceIntent);
        assertTrue(sink.durable.get(2) instanceof WalEntry.MaterialIntent);
        assertTrue(sink.durable.get(3) instanceof WalEntry.RunEnd);
        assertEquals(1L, mats.restart().durableTx(), "the saved inventory carries run 1 as its transaction id");
    }

    @Test
    void aHandOverThatDoesNotFitPausesBeforeTheWorldChangesAndNothingIsDropped() {
        BlockSpec grass = BlockSpec.of("minecraft:grass_block");
        IntPos pos = new IntPos(0, 63, 0);
        Placement cut = new Placement(0, pos, BlockSpec.of("minecraft:cobblestone"), Map.of(), "found", BuildPhase.SITE_PREP,
                PlacerId.SIMPLE, VerifyMode.EXACT, ReplacePolicy.TERRAFORM, null);
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 2, 70, 1), List.of(cut));
        FakeWorld w = new FakeWorld();
        w.setBlock(pos, grass, CellTrait.TERRAFORMABLE);
        FakeMaterials mats = new FakeMaterials().with("minecraft:cobblestone", 1);
        mats.room = 1;
        ExecutionContext c = fresh(m, w, MaterialPolicy.SURVIVAL_CONSUME, mats);
        StepReport r = ConstructionExecutor.run(c, 0, PLENTY);
        assertEquals(PauseReason.NO_ROOM, r.pause(), "the dirt from the cut would not fit");
        assertEquals(grass, w.blockAt(pos), "not cut yet");
        assertEquals(1, mats.count("minecraft:cobblestone"), "not charged yet");
        mats.room = FakeMaterials.UNLIMITED;
        assertNull(ConstructionExecutor.run(c, 0, PLENTY).pause());
        assertEquals(1, mats.count("minecraft:dirt"));
    }

    @Test
    void aRepairRoundThatFindsOurBlockAlreadyRightPlacesAndChargesNothing() {
        PlacementManifest m = TestManifests.smallHut();
        FakeWorld w = new FakeWorld();
        FakeMaterials mats = new FakeMaterials().with("minecraft:cobblestone", 10).with("minecraft:oak_planks", 16);
        ExecutionContext first = fresh(m, w, MaterialPolicy.SURVIVAL_CONSUME, mats);
        ConstructionExecutor.run(first, 0, PLENTY);
        int writes = w.log.size();
        JobProgram repair = JobProgram.repair(m, List.of(0), 1);
        ExecutionContext round = ctx(first.job(), repair, w, mats, first.journal(), first.ledgers(), first.registry(),
                first.outcome(), false);
        assertNull(ConstructionExecutor.run(round, 0, PLENTY).pause());
        assertEquals(writes, w.log.size(), "the block is there and right: no write");
        assertEquals(1, mats.count("minecraft:cobblestone"), "and no second charge under the repair round's key");
        assertTrue(first.outcome().skipped().stream().anyMatch(sk -> SkippedPlacement.ALREADY_THERE.equals(sk.reason())));
        w.setBlock(m.placements().get(0).pos(), BlockSpec.AIR, CellTrait.REPLACEABLE);
        assertNull(ConstructionExecutor.run(round, 0, PLENTY).pause());
        assertEquals(0, mats.count("minecraft:cobblestone"), "a real repair is its own record and its own charge");
        assertTrue(first.journal().at(JobProgram.LEDGER_ROUND_STRIDE).isPresent(), "journaled under the round's key");
    }

    @Test
    void aFirstRoundStepNeverTakesTheProjectsBlockInAnotherStateAsItsOwn() {
        IntPos pos = new IntPos(1, 64, 1);
        BlockSpec north = BlockSpec.of("minecraft:oak_stairs", "facing", "north");
        BlockSpec east = BlockSpec.of("minecraft:oak_stairs", "facing", "east");
        PlacementManifest m = TestManifests.of(new Box(0, 60, 0, 4, 70, 4), List.of(TestManifests.put(1, 64, 1, "minecraft:oak_stairs",
                "facing", "north")));
        FakeWorld w = new FakeWorld();
        w.setBlock(pos, east);
        ExecutionContext c = fresh(m, w, MaterialPolicy.CREATIVE_FREE, MaterialPort.FREE);
        // the project placed the north-facing stair earlier (another job); a player has turned it east since
        c.registry().apply("job-0", new JournalRecord(0, pos, BlockSpec.AIR, false, north, 0));
        StepReport r = ConstructionExecutor.run(c, 0, PLENTY);
        assertEquals(PauseReason.SITE_CHANGED, r.pause(), "a first-round step (BUILD, MODIFY) does not overwrite it");
        assertEquals(east, w.blockAt(pos));
        assertEquals(1, c.outcome().conflicts().size());
        // only a repair round re-places the project's own block of the same id in a wrong state
        ExecutionContext repair = ctx(c.job(), JobProgram.repair(m, List.of(0), 1), w, MaterialPort.FREE, c.journal(), c.ledgers(),
                c.registry(), c.outcome(), false);
        assertNull(ConstructionExecutor.run(repair, 0, PLENTY).pause());
        assertEquals(north, w.blockAt(pos));
    }

    @Test
    void anAssembledAwayBlockIsPlacedBeforeItsAssemblyAndNeverAgainAfterIt() {
        IntPos pos = new IntPos(1, 64, 1);
        Placement moved = new Placement(0, pos, BlockSpec.of("minecraft:oak_planks"), Map.of(), "wall", BuildPhase.STRUCTURE,
                PlacerId.SIMPLE, VerifyMode.ASSEMBLED_AWAY, ReplacePolicy.REPLACEABLE, "group-1");
        PlacementManifest m = TestManifests.of(new Box(0, 60, 0, 4, 70, 4), List.of(moved));
        FakeWorld w = new FakeWorld();
        FakeMaterials mats = new FakeMaterials().with("minecraft:oak_planks", 2);
        ExecutionContext c = fresh(m, w, MaterialPolicy.SURVIVAL_CONSUME, mats);
        assertNull(ConstructionExecutor.run(c, 0, PLENTY).pause());
        assertEquals(BlockSpec.of("minecraft:oak_planks"), w.blockAt(pos), "before the assembly the block must be there");
        // the assembly (P10/P13) moves the block into its contraption: the position is air now
        c.registry().putAssembly(new AssemblyResult("group-1", "contraption-1", 1, 5L));
        w.setBlock(pos, BlockSpec.AIR, CellTrait.REPLACEABLE);
        int writes = w.log.size();
        ExecutionContext again = ctx(c.job(), c.program(), w, mats, new Journal(), c.ledgers(), c.registry(), c.outcome(), false);
        assertNull(ConstructionExecutor.run(again, 0, PLENTY).pause());
        assertEquals(writes, w.log.size(), "never placed again, even by a step with no journal record of its own");
        assertEquals(BlockSpec.AIR, w.blockAt(pos));
        assertEquals(1, mats.count("minecraft:oak_planks"), "charged once");
    }

    @Test
    void anOwedGiveWithNowhereToGoPausesNoRoomAndSettlesNothing() {
        // an earlier run wrote the cut and charged for it, but the ground owed to the owner was not settled yet
        IntPos pos = new IntPos(0, 63, 0);
        Placement sunk = new Placement(0, pos, BlockSpec.of("minecraft:cobblestone"), Map.of(), "found",
                BuildPhase.SITE_PREP, PlacerId.SIMPLE, VerifyMode.EXACT, ReplacePolicy.TERRAFORM, null);
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 1, 70, 1), List.of(sunk));
        FakeWorld w = new FakeWorld();
        w.setBlock(pos, BlockSpec.of("minecraft:cobblestone"));
        Journal journal = new Journal();
        journal.record(new JournalRecord(0, pos, BlockSpec.of("minecraft:grass_block"), false,
                BlockSpec.of("minecraft:cobblestone"), 0, true));
        LedgerBook ledgers = new LedgerBook();
        ledgers.of("job-1").recordConsumed(0, List.of(new ItemCount("minecraft:cobblestone", 1)));
        PlacedRegistry registry = new PlacedRegistry("claim-job-1");
        registry.apply("job-1", journal.at(0).orElseThrow());
        FakeMaterials mats = new FakeMaterials().with("minecraft:cobblestone", 1);
        mats.room = 1;
        ExecutionContext c = ctx(running(MaterialPolicy.SURVIVAL_CONSUME, 1), JobProgram.build(m), w, mats, journal, ledgers,
                registry, new JobOutcome(), false);
        StepReport r = ConstructionExecutor.run(c, 0, PLENTY);
        assertEquals(PauseReason.NO_ROOM, r.pause(), "the dirt owed has nowhere to go: not a shortage of materials");
        assertEquals(List.of(new ItemCount("minecraft:dirt", 1)), r.shortage());
        assertTrue(ledgers.of("job-1").yieldedView().isEmpty(), "the give was not settled");
        assertEquals(1, journal.size(), "nothing new was written");
        assertEquals("minecraft:cobblestone", w.blockAt(pos).blockId(), "the world was not touched");
        mats.room = FakeMaterials.UNLIMITED;
        assertNull(ConstructionExecutor.run(c, 0, PLENTY).pause());
        assertEquals(1, mats.count("minecraft:dirt"), "the owed dirt is handed over once room frees up");
    }

    @Test
    void aRunStoppedByMissingMaterialsStillWritesItsEnd() {
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 2, 70, 1),
                List.of(TestManifests.put(0, 64, 0, "minecraft:oak_planks")));
        FakeWorld w = new FakeWorld();
        ExecutionContext c = fresh(m, w, MaterialPolicy.SURVIVAL_CONSUME, new FakeMaterials());
        StepReport r = ConstructionExecutor.run(c, 0, PLENTY);
        assertEquals(PauseReason.MATERIALS_MISSING, r.pause());
        WriteAheadLog.MemorySink sink = (WriteAheadLog.MemorySink) c.wal().sink();
        assertTrue(sink.durable.get(0) instanceof WalEntry.RunStart, "the run opened the log");
        assertTrue(sink.durable.get(sink.durable.size() - 1) instanceof WalEntry.RunEnd,
                "the run ends on the log even though nothing changed: without it recovery reads it as cut short");
        WalRecovery.Result res = WalRecovery.recover("job-1", sink.durable, c.program(), new Journal(),
                new PlacedRegistry("claim-job-1"), new LedgerBook(), w, 0L, WalRecovery.Resolution.NONE);
        assertFalse(res.ambiguous(), "a cleanly stopped run is not left for the owner to answer");
        assertTrue(res.reasons().isEmpty());
    }

    @Test
    void aRunStoppedForRoomStillWritesItsEnd() {
        IntPos pos = new IntPos(0, 63, 0);
        Placement cut = new Placement(0, pos, BlockSpec.of("minecraft:cobblestone"), Map.of(), "found",
                BuildPhase.SITE_PREP, PlacerId.SIMPLE, VerifyMode.EXACT, ReplacePolicy.TERRAFORM, null);
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 2, 70, 1), List.of(cut));
        FakeWorld w = new FakeWorld();
        w.setBlock(pos, BlockSpec.of("minecraft:grass_block"), CellTrait.TERRAFORMABLE);
        FakeMaterials mats = new FakeMaterials().with("minecraft:cobblestone", 1);
        mats.room = 1;
        ExecutionContext c = fresh(m, w, MaterialPolicy.SURVIVAL_CONSUME, mats);
        assertEquals(PauseReason.NO_ROOM, ConstructionExecutor.run(c, 0, PLENTY).pause());
        WriteAheadLog.MemorySink sink = (WriteAheadLog.MemorySink) c.wal().sink();
        assertTrue(sink.durable.get(0) instanceof WalEntry.RunStart);
        assertTrue(sink.durable.get(sink.durable.size() - 1) instanceof WalEntry.RunEnd,
                "the run ends on the log even though nothing changed");
        assertFalse(WalRecovery.recover("job-1", sink.durable, c.program(), new Journal(), new PlacedRegistry("claim-job-1"),
                new LedgerBook(), w, 0L, WalRecovery.Resolution.NONE).ambiguous());
    }

    @Test
    void aRunWhereTheOnlyStepIsDeniedStillWritesItsEnd() {
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 2, 70, 1),
                List.of(TestManifests.put(0, 64, 0, "minecraft:oak_planks")));
        FakeWorld w = new FakeWorld();
        w.deny(new IntPos(0, 64, 0));
        ExecutionContext c = fresh(m, w, MaterialPolicy.SURVIVAL_CONSUME,
                new FakeMaterials().with("minecraft:oak_planks", 1));
        StepReport r = ConstructionExecutor.run(c, 0, PLENTY);
        assertNull(r.pause(), "a denial is a skip, not a pause");
        assertEquals(1, r.cursor());
        WriteAheadLog.MemorySink sink = (WriteAheadLog.MemorySink) c.wal().sink();
        assertTrue(sink.durable.get(0) instanceof WalEntry.RunStart);
        assertTrue(sink.durable.get(sink.durable.size() - 1) instanceof WalEntry.RunEnd,
                "the run ends on the log even though nothing changed");
        assertFalse(WalRecovery.recover("job-1", sink.durable, c.program(), new Journal(), new PlacedRegistry("claim-job-1"),
                new LedgerBook(), w, 0L, WalRecovery.Resolution.NONE).ambiguous());
    }

    @Test
    void anEntityInTheWayPausesTheBuildWithoutWritingRecordingOrCharging() {
        PlacementManifest m = TestManifests.smallHut();
        FakeWorld w = new FakeWorld();
        IntPos spot = m.placements().get(3).pos();
        w.occupy(spot);
        FakeMaterials mats = new FakeMaterials().with("minecraft:cobblestone", 9).with("minecraft:oak_planks", 16);
        ExecutionContext c = fresh(m, w, MaterialPolicy.SURVIVAL_CONSUME, mats);
        StepReport r = ConstructionExecutor.run(c, 0, PLENTY);
        assertEquals(PauseReason.ENTITY_IN_WAY, r.pause());
        assertEquals(3, r.cursor(), "the cursor stays on the blocked position: it is not skipped");
        assertEquals(BlockSpec.AIR, w.blockAt(spot), "nothing was written over the one standing there");
        assertEquals(3, c.journal().size(), "the blocked position got no journal record");
        assertEquals(3, c.registry().size(), "and no registry entry");
        assertFalse(c.outcome().skipped().stream().anyMatch(sk -> sk.pos().equals(spot)),
                "a wait is not a skip: the position is still owed");
        assertEquals(6, mats.count("minecraft:cobblestone"), "three foundations paid, the blocked one not");
        assertEquals(16, mats.count("minecraft:oak_planks"));
    }

    @Test
    void theBuildResumesAtTheBlockedSpotOnceTheEntityStepsAside() {
        PlacementManifest m = TestManifests.smallHut();
        FakeWorld w = new FakeWorld();
        IntPos spot = m.placements().get(3).pos();
        w.occupy(spot);
        FakeMaterials mats = new FakeMaterials().with("minecraft:cobblestone", 9).with("minecraft:oak_planks", 16);
        ExecutionContext c = fresh(m, w, MaterialPolicy.SURVIVAL_CONSUME, mats);
        StepReport paused = ConstructionExecutor.run(c, 0, PLENTY);
        assertEquals(PauseReason.ENTITY_IN_WAY, paused.pause());
        w.leave(spot);
        StepReport r = ConstructionExecutor.run(c, paused.cursor(), PLENTY);
        assertNull(r.pause());
        assertEquals(25, r.cursor());
        assertEquals(m.placements().get(3).block(), w.blockAt(spot), "the position that waited is really built");
        assertEquals(0, mats.count("minecraft:cobblestone"));
        assertEquals(0, mats.count("minecraft:oak_planks"), "every block is charged once, the retried one included");
        assertEquals(25, c.journal().size());
    }

    @Test
    void anEntityInTheWayHoldsUpOnlyItsOwnPosition() {
        PlacementManifest m = TestManifests.smallHut();
        FakeWorld w = new FakeWorld();
        IntPos first = m.placements().get(3).pos();
        IntPos later = m.placements().get(7).pos();
        w.occupy(first);
        w.occupy(later);
        ExecutionContext c = fresh(m, w, MaterialPolicy.CREATIVE_FREE, MaterialPort.FREE);
        StepReport r = ConstructionExecutor.run(c, 0, PLENTY);
        assertEquals(PauseReason.ENTITY_IN_WAY, r.pause());
        assertEquals(3, r.cursor());
        w.leave(first);
        StepReport next = ConstructionExecutor.run(c, r.cursor(), PLENTY);
        assertEquals(PauseReason.ENTITY_IN_WAY, next.pause(), "the next blocked position waits in turn");
        assertEquals(7, next.cursor());
        assertEquals(m.placements().get(3).block(), w.blockAt(first), "the cleared position was placed");
        assertEquals(BlockSpec.AIR, w.blockAt(later), "the still-blocked one was not");
        w.leave(later);
        assertNull(ConstructionExecutor.run(c, next.cursor(), PLENTY).pause());
        assertEquals(25, c.journal().size());
    }

    @Test
    void aFailedApplyAfterTheWriteLeavesTheBlockJournaledAndPausesForRecovery() {
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 2, 70, 1),
                List.of(TestManifests.put(0, 64, 0, "minecraft:oak_planks")));
        FakeWorld w = new FakeWorld();
        FakeMaterials mats = new FakeMaterials().with("minecraft:oak_planks", 1);
        mats.failNextApply = true;
        ExecutionContext c = fresh(m, w, MaterialPolicy.SURVIVAL_CONSUME, mats);
        IntPos pos = new IntPos(0, 64, 0);
        StepReport r = ConstructionExecutor.run(c, 0, PLENTY);
        assertEquals(PauseReason.RECOVERY_NEEDED, r.pause(), "the apply failed after the write: recover, do not crash");
        assertEquals(0, r.cursor());
        assertEquals(BlockSpec.of("minecraft:oak_planks"), w.blockAt(pos), "the block was really placed");
        assertTrue(c.journal().at(0).isPresent(), "the write is journaled before the settle: a rollback can remove it");
        assertTrue(c.registry().contains(pos), "the position is held as this job's");
        assertFalse(c.ledgers().of("job-1").isConsumed(0), "nothing is claimed paid");
        assertEquals(1, mats.count("minecraft:oak_planks"), "the refused apply changed nothing");
        StepReport again = ConstructionExecutor.run(c, r.cursor(), PLENTY);
        assertNull(again.pause());
        assertEquals(0, mats.count("minecraft:oak_planks"), "the journaled charge is settled on the retry, once");
        assertTrue(c.ledgers().of("job-1").isConsumed(0));
    }
}
