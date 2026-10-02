package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.ConflictKind;
import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConstructionExecutorRestoreTest {
    private static final int PLENTY = 10_000;

    /** Builds the small hut in survival, then returns the shared state for a rollback program. */
    private record Built(PlacementManifest m, FakeWorld w, FakeMaterials mats, LedgerBook ledgers, PlacedRegistry registry) {
    }

    private static Built build() {
        PlacementManifest m = TestManifests.smallHut();
        FakeWorld w = new FakeWorld();
        FakeMaterials mats = new FakeMaterials().with("minecraft:cobblestone", 9).with("minecraft:oak_planks", 16);
        LedgerBook ledgers = new LedgerBook();
        PlacedRegistry registry = new PlacedRegistry("claim-job-1");
        ExecutionContext c = ConstructionExecutorTest.ctx(ConstructionExecutorTest.running(MaterialPolicy.SURVIVAL_CONSUME, 25),
                JobProgram.build(m), w, mats, new Journal(), ledgers, registry, new JobOutcome(), false);
        ConstructionExecutor.run(c, 0, PLENTY);
        return new Built(m, w, mats, ledgers, registry);
    }

    private static List<RestoreItem> undoAll(PlacedRegistry registry) {
        List<RestoreItem> out = new ArrayList<>();
        registry.placed().entrySet().stream()
                .sorted((a, b) -> a.getKey().y() != b.getKey().y() ? b.getKey().y() - a.getKey().y()
                        : a.getKey().z() != b.getKey().z() ? a.getKey().z() - b.getKey().z() : a.getKey().x() - b.getKey().x())
                .forEach(e -> out.add(new RestoreItem(e.getKey(), e.getValue().placed(), Set.of(), e.getValue().before(),
                        e.getValue().jobId(), e.getValue().placementIndex(), false)));
        return out;
    }

    private static ExecutionContext rollback(Built b, List<RestoreItem> items) {
        ConstructionJob job = ConstructionJob.create("job-2", ConstructionJobTest.OWNER, TestManifests.DIM, "h", JobKind.ROLLBACK,
                "job-1", items.size(), "claim-job-1", MaterialPolicy.SURVIVAL_CONSUME, 0L, List.of())
                .on(JobEvent.ADMITTED).on(JobEvent.START);
        return ConstructionExecutorTest.ctx(job, new JobProgram(items, List.of()), b.w(), b.mats(), new Journal(items.size()),
                b.ledgers(), b.registry(), new JobOutcome(), false);
    }

    @Test
    void rollbackRestoresEveryBlockTopDownAndReturnsExactlyWhatWasConsumedOnce() {
        Built b = build();
        ExecutionContext c = rollback(b, undoAll(b.registry()));
        StepReport r = ConstructionExecutor.run(c, 0, PLENTY);
        assertNull(r.pause());
        for (IntPos p : b.m().placements().stream().map(x -> x.pos()).toList()) {
            assertEquals(BlockSpec.AIR, b.w().blockAt(p));
        }
        assertEquals(9, b.mats().count("minecraft:cobblestone"));
        assertEquals(16, b.mats().count("minecraft:oak_planks"));
        assertEquals(0, b.registry().size(), "every position is back to its pre-build block");
        assertTrue(b.w().log.get(25).startsWith("restore 0,65,0"), "top row first");
        StepReport again = ConstructionExecutor.run(rollback(b, undoAll(b.registry())), 0, PLENTY);
        assertEquals(0, again.cursor(), "nothing left to roll back");
        assertEquals(16, b.mats().count("minecraft:oak_planks"), "never returned twice");
    }

    @Test
    void aBlockThePlayerReplacedIsAConflictAndIsLeftAlone() {
        Built b = build();
        IntPos wall = new IntPos(0, 64, 0);
        b.w().setBlock(wall, BlockSpec.of("minecraft:gold_block"));
        ExecutionContext c = rollback(b, undoAll(b.registry()));
        StepReport r = ConstructionExecutor.run(c, 0, PLENTY);
        assertNull(r.pause());
        assertEquals("minecraft:gold_block", b.w().blockAt(wall).blockId());
        assertEquals(1, r.conflicts().size());
        assertEquals(ConflictKind.PLAYER_MODIFIED, r.conflicts().get(0).kind());
        assertEquals(15, b.mats().count("minecraft:oak_planks"), "no return for the plank the player took away");
    }

    @Test
    void aContainerTheProjectPlacedDropsItsItemsBeforeRemoval() {
        Built b = build();
        IntPos chest = new IntPos(1, 64, 1);
        b.w().set(chest, WorldCell.withBlockEntity(BlockSpec.of("minecraft:chest"), "minecraft:chest"));
        b.w().putItems(chest, 5);
        b.registry().apply("job-1", new JournalRecord(99, chest, BlockSpec.AIR, false, BlockSpec.of("minecraft:chest"), 99));
        List<RestoreItem> items = List.of(new RestoreItem(chest, BlockSpec.of("minecraft:chest"), Set.of(), BlockSpec.AIR, "job-1",
                99, true));
        ExecutionContext c = rollback(b, items);
        ConstructionExecutor.run(c, 0, PLENTY);
        int drop = b.w().log.indexOf("drop 1,64,1 5");
        assertTrue(drop >= 0, "the items were dropped, not deleted");
        assertEquals("restore 1,64,1 minecraft:air", b.w().log.get(drop + 1));
        assertTrue(((WriteAheadLog.MemorySink) c.wal().sink()).durable.contains(new WalEntry.DropIntent("job-2", 1, chest)),
                "the drop is logged before it happens (best effort: dropped items are chunk data)");
    }

    @Test
    void aDoorIsRestoredAsOnePieceEvenAcrossTheAllowanceAndSettledOnce() {
        FakeWorld w = new FakeWorld();
        IntPos upper = new IntPos(2, 65, 0);
        IntPos lower = new IntPos(2, 64, 0);
        IntPos wall = new IntPos(0, 64, 0);
        BlockSpec up = BlockSpec.of("minecraft:oak_door", "half", "upper");
        BlockSpec low = BlockSpec.of("minecraft:oak_door", "half", "lower");
        w.setBlock(upper, up);
        w.setBlock(lower, low);
        w.setBlock(wall, BlockSpec.of("minecraft:stone"));
        List<RestoreItem> items = List.of(
                new RestoreItem(upper, up, Set.of("open", "powered"), BlockSpec.AIR, "job-1", 0, true),
                new RestoreItem(lower, low, Set.of("open", "powered"), BlockSpec.AIR, "job-1", 1, true),
                new RestoreItem(wall, BlockSpec.of("minecraft:stone"), Set.of(), BlockSpec.AIR, "job-1", 2, true));
        Built b = new Built(TestManifests.smallHut(), w, new FakeMaterials(), new LedgerBook(), new PlacedRegistry("claim-job-1"));
        // the registry holds what the source job placed: a removal may only take a block that is provably its job's
        b.registry().apply("job-1", new JournalRecord(0, upper, BlockSpec.AIR, false, up, 0));
        b.registry().apply("job-1", new JournalRecord(1, lower, BlockSpec.AIR, false, low, 1));
        b.registry().apply("job-1", new JournalRecord(2, wall, BlockSpec.AIR, false, BlockSpec.of("minecraft:stone"), 2));
        StepReport first = ConstructionExecutor.run(rollback(b, items), 0, 1);
        assertEquals(2, first.cursor(), "the allowance of 1 does not split the door");
        assertEquals(List.of("drop 2,65,0 0", "restore 2,65,0 minecraft:air", "drop 2,64,0 0", "restore 2,64,0 minecraft:air",
                "settle 2,65,0 2,64,0"), w.log.subList(w.log.size() - 5, w.log.size()));
        assertEquals(0, first.conflicts().size(), "the lower half is still there when its turn comes");
        StepReport second = ConstructionExecutor.run(rollback(b, items), 2, 1);
        assertEquals(3, second.cursor());
        assertEquals("settle 0,64,0", w.log.get(w.log.size() - 1));
    }

    @Test
    void takingBackCutGroundWaitsForTheItems() {
        FakeWorld w = new FakeWorld();
        IntPos p = new IntPos(0, 63, 0);
        w.setBlock(p, BlockSpec.of("minecraft:cobblestone"));
        LedgerBook ledgers = new LedgerBook();
        ledgers.of("job-1").recordYield(0, List.of(new ItemCount("minecraft:dirt", 1)));
        FakeMaterials mats = new FakeMaterials();
        RestoreItem item = new RestoreItem(p, BlockSpec.of("minecraft:cobblestone"), Set.of(), BlockSpec.of("minecraft:grass_block"),
                "job-1", 0, false);
        Built b = new Built(TestManifests.smallHut(), w, mats, ledgers, new PlacedRegistry("claim-job-1"));
        // the cut the item undoes is this job's write: grass was there, cobblestone was placed under key 0
        b.registry().apply("job-1", new JournalRecord(0, p, BlockSpec.of("minecraft:grass_block"), false,
                BlockSpec.of("minecraft:cobblestone"), 0));
        StepReport r = ConstructionExecutor.run(rollback(b, List.of(item)), 0, PLENTY);
        assertEquals(PauseReason.MATERIALS_MISSING, r.pause());
        assertEquals(List.of(new ItemCount("minecraft:dirt", 1)), r.shortage());
        assertEquals("minecraft:cobblestone", w.blockAt(p).blockId(), "no ground is made from nothing");
        mats.with("minecraft:dirt", 1);
        assertNull(ConstructionExecutor.run(rollback(b, List.of(item)), 0, PLENTY).pause());
        assertEquals("minecraft:grass_block", w.blockAt(p).blockId());
        assertEquals(0, mats.count("minecraft:dirt"));
        assertTrue(ledgers.of("job-1").isReclaimed(0), "the ledger shows the reclaim, so it is never taken twice");
    }

    @Test
    void onlyThePartOfAPieceThatWasReallyRemovedGetsItsNeighboursUpdated() {
        FakeWorld w = new FakeWorld();
        IntPos upper = new IntPos(2, 65, 0);
        IntPos lower = new IntPos(2, 64, 0);
        BlockSpec up = BlockSpec.of("minecraft:oak_door", "half", "upper");
        BlockSpec low = BlockSpec.of("minecraft:oak_door", "half", "lower");
        w.setBlock(upper, BlockSpec.of("minecraft:gold_block"));
        w.setBlock(lower, low);
        List<RestoreItem> items = List.of(
                new RestoreItem(upper, up, Set.of("open", "powered"), BlockSpec.AIR, "job-1", 0, true),
                new RestoreItem(lower, low, Set.of("open", "powered"), BlockSpec.AIR, "job-1", 1, true));
        Built b = new Built(TestManifests.smallHut(), w, new FakeMaterials(), new LedgerBook(), new PlacedRegistry("claim-job-1"));
        b.registry().apply("job-1", new JournalRecord(0, upper, BlockSpec.AIR, false, up, 0));
        b.registry().apply("job-1", new JournalRecord(1, lower, BlockSpec.AIR, false, low, 1));
        StepReport r = ConstructionExecutor.run(rollback(b, items), 0, PLENTY);
        assertEquals(1, r.conflicts().size(), "the gold block is someone else's");
        assertEquals("settle 2,64,0", w.log.get(w.log.size() - 1), "the conflicted half is not settled");
        assertEquals("minecraft:gold_block", w.blockAt(upper).blockId());
    }

    @Test
    void aRemovalDoneBeforeACrashStillSettlesWhatItOwesButAPlayersEmptyingOwesNothing() {
        Built b = build();
        List<RestoreItem> all = undoAll(b.registry());
        ExecutionContext first = rollback(b, all);
        ConstructionExecutor.run(first, 0, PLENTY);
        assertEquals(16, b.mats().count("minecraft:oak_planks"));
        // a crash lost the refunds but not the removals: the journal says removed, the ledger lost the returns
        MaterialLedger ledger = b.ledgers().of("job-1");
        for (Integer key : List.copyOf(ledger.returnedView().keySet())) {
            ledger.unrecord(MaterialOp.RETURN, key, ledger.returned(key));
        }
        b.mats().with("minecraft:oak_planks", -16).with("minecraft:cobblestone", -9);
        ExecutionContext again = ConstructionExecutorTest.ctx(first.job(), first.program(), b.w(), b.mats(), first.journal(),
                b.ledgers(), b.registry(), new JobOutcome(), false);
        assertNull(ConstructionExecutor.run(again, 0, PLENTY).pause());
        assertEquals(16, b.mats().count("minecraft:oak_planks"), "each refund owed is paid once");
        assertEquals(9, b.mats().count("minecraft:cobblestone"));
        Built other = build();
        IntPos broken = new IntPos(0, 65, 0);
        other.w().setBlock(broken, BlockSpec.AIR, CellTrait.REPLACEABLE);
        ConstructionExecutor.run(rollback(other, undoAll(other.registry())), 0, PLENTY);
        assertEquals(15, other.mats().count("minecraft:oak_planks") + other.mats().count("minecraft:cobblestone") - 9,
                "the block a player broke was not this job's to refund");
    }

    @Test
    void aRefundThatDoesNotFitPausesBeforeTheBlockIsRemovedAndNothingIsDropped() {
        Built b = build();
        b.mats().room = 0;
        List<RestoreItem> items = undoAll(b.registry());
        StepReport r = ConstructionExecutor.run(rollback(b, items), 0, PLENTY);
        assertEquals(PauseReason.NO_ROOM, r.pause(), "the owner's inventory is full: the refund would have to be dropped");
        assertEquals(items.get(0).expectedNow(), b.w().blockAt(items.get(0).pos()), "not removed yet");
        b.mats().room = FakeMaterials.UNLIMITED;
        assertNull(ConstructionExecutor.run(rollback(b, undoAll(b.registry())), 0, PLENTY).pause());
        assertEquals(16, b.mats().count("minecraft:oak_planks"));
    }

    @Test
    void aPositionIsRemovedOnlyWhenTheRegistryProvesTheSourceJobWroteIt() {
        FakeWorld w = new FakeWorld();
        BlockSpec stone = BlockSpec.of("minecraft:stone");
        BlockSpec cobble = BlockSpec.of("minecraft:cobblestone");
        BlockSpec grass = BlockSpec.of("minecraft:grass_block");
        IntPos noEntry = new IntPos(0, 64, 0);
        IntPos otherJob = new IntPos(1, 64, 0);
        IntPos otherKey = new IntPos(2, 64, 0);
        IntPos ours = new IntPos(3, 64, 0);
        IntPos chained = new IntPos(4, 64, 0);
        for (IntPos p : List.of(noEntry, otherJob, otherKey, ours)) {
            w.setBlock(p, stone);
        }
        w.setBlock(chained, cobble);
        PlacedRegistry registry = new PlacedRegistry("claim-job-1");
        registry.apply("job-9", new JournalRecord(4, otherJob, BlockSpec.AIR, false, stone, 4));
        registry.apply("job-1", new JournalRecord(9, otherKey, BlockSpec.AIR, false, stone, 9));
        registry.apply("job-1", new JournalRecord(5, ours, BlockSpec.AIR, false, stone, 5));
        // a foundation laid over this job's own ground cut: the entry holds the current write's key, the cut's is earlier
        registry.apply("job-1", new JournalRecord(0, chained, grass, false, BlockSpec.AIR, 0, true));
        registry.apply("job-1", new JournalRecord(1, chained, BlockSpec.AIR, false, cobble, 1));
        List<RestoreItem> items = List.of(
                new RestoreItem(noEntry, stone, Set.of(), BlockSpec.AIR, "job-1", 0, false),
                new RestoreItem(otherJob, stone, Set.of(), BlockSpec.AIR, "job-1", 1, false),
                new RestoreItem(otherKey, stone, Set.of(), BlockSpec.AIR, "job-1", 2, false),
                new RestoreItem(ours, stone, Set.of(), BlockSpec.AIR, "job-1", 5, false),
                new RestoreItem(chained, cobble, Set.of(), grass, "job-1", 0, false));
        Built b = new Built(TestManifests.smallHut(), w, new FakeMaterials(), new LedgerBook(), registry);
        StepReport r = ConstructionExecutor.run(rollback(b, items), 0, PLENTY);
        assertNull(r.pause());
        assertEquals(3, r.conflicts().size(), "no entry, another job's entry, a key the entry does not list: all stay");
        assertTrue(r.conflicts().stream().allMatch(cf -> cf.kind() == ConflictKind.PLAYER_MODIFIED));
        assertEquals(stone, w.blockAt(noEntry), "a block that was always there is not erased");
        assertEquals(stone, w.blockAt(otherJob), "another job's block is not this job's to remove");
        assertEquals(stone, w.blockAt(otherKey), "the entry's key is not the step's key");
        assertEquals(BlockSpec.AIR, w.blockAt(ours), "the job's own write still comes out");
        assertEquals(grass, w.blockAt(chained), "an earlier key of the entry's chain still owns the position");
        assertFalse(registry.contains(ours));
        assertFalse(registry.contains(chained));
    }

    @Test
    void aRefundOwedOnAnAlreadyRemovedBlockAlsoWaitsForRoom() {
        // the removal is journaled, the block is air, but its refund never reached the owner
        FakeWorld w = new FakeWorld();
        IntPos pos = new IntPos(0, 64, 0);
        LedgerBook ledgers = new LedgerBook();
        ledgers.of("job-1").recordConsumed(0, List.of(new ItemCount("minecraft:oak_planks", 1)));
        Journal journal = new Journal(1);
        journal.record(new JournalRecord(JournalRecord.restoreIndex(0), pos, BlockSpec.of("minecraft:oak_planks"), false,
                BlockSpec.AIR, JournalRecord.NO_LEDGER_KEY));
        FakeMaterials mats = new FakeMaterials();
        mats.room = 0;
        ConstructionJob job = ConstructionJob.create("job-2", ConstructionJobTest.OWNER, TestManifests.DIM, "h",
                JobKind.ROLLBACK, "job-1", 1, "claim-job-1", MaterialPolicy.SURVIVAL_CONSUME, 0L, List.of())
                .on(JobEvent.ADMITTED).on(JobEvent.START);
        List<RestoreItem> items = List.of(new RestoreItem(pos, BlockSpec.of("minecraft:oak_planks"), Set.of(), BlockSpec.AIR,
                "job-1", 0, false));
        ExecutionContext c = ConstructionExecutorTest.ctx(job, new JobProgram(items, List.of()), w, mats, journal, ledgers,
                new PlacedRegistry("claim-job-1"), new JobOutcome(), false);
        StepReport r = ConstructionExecutor.run(c, 0, PLENTY);
        assertEquals(PauseReason.NO_ROOM, r.pause(), "the refund owed has nowhere to go: not a shortage of materials");
        assertEquals(List.of(new ItemCount("minecraft:oak_planks", 1)), r.shortage());
        assertFalse(ledgers.of("job-1").isReturned(0), "the refund is still owed");
        assertEquals(0, mats.count("minecraft:oak_planks"));
        mats.room = FakeMaterials.UNLIMITED;
        assertNull(ConstructionExecutor.run(c, 0, PLENTY).pause());
        assertEquals(1, mats.count("minecraft:oak_planks"), "the owed refund is paid once room frees up");
    }

    @Test
    void aBlockingEntityPausesARollbackRestoreAndResumesWhenItLeaves() {
        FakeWorld w = new FakeWorld();
        IntPos p = new IntPos(0, 63, 0);
        BlockSpec cut = BlockSpec.of("minecraft:cobblestone");
        BlockSpec ground = BlockSpec.of("minecraft:grass_block");
        w.setBlock(p, cut);
        w.occupy(p);
        PlacedRegistry registry = new PlacedRegistry("claim-job-1");
        registry.apply("job-1", new JournalRecord(0, p, ground, false, cut, 0));
        RestoreItem item = new RestoreItem(p, cut, Set.of(), ground, "job-1", 0, false);
        Built b = new Built(TestManifests.smallHut(), w, new FakeMaterials(), new LedgerBook(), registry);
        ExecutionContext c = rollback(b, List.of(item));
        StepReport r = ConstructionExecutor.run(c, 0, PLENTY);
        assertEquals(PauseReason.ENTITY_IN_WAY, r.pause(), "putting the ground back over someone must wait");
        assertEquals(0, r.cursor());
        assertEquals(cut, w.blockAt(p), "the restore wrote nothing");
        assertEquals(0, c.journal().size(), "nothing was recorded");
        w.leave(p);
        StepReport done = ConstructionExecutor.run(c, 0, PLENTY);
        assertNull(done.pause());
        assertEquals(ground, w.blockAt(p), "once clear, the retry puts the ground back");
    }

    @Test
    void anEntityNeverBlocksARemovalToAir() {
        Built b = build();
        List<RestoreItem> items = undoAll(b.registry());
        IntPos occupied = items.get(0).pos();
        b.w().occupy(occupied);
        StepReport r = ConstructionExecutor.run(rollback(b, items), 0, PLENTY);
        assertNull(r.pause(), "air has no collision shape: a removal always goes through, whoever stands there");
        assertEquals(BlockSpec.AIR, b.w().blockAt(occupied));
    }

    @Test
    void aFailedApplyAfterARemovalLeavesTheRemovalJournaledAndPausesForRecovery() {
        Built b = build();
        List<RestoreItem> items = undoAll(b.registry());
        ExecutionContext c = rollback(b, items);
        b.mats().failNextApply = true;
        StepReport r = ConstructionExecutor.run(c, 0, PLENTY);
        assertEquals(PauseReason.RECOVERY_NEEDED, r.pause(), "the give-back failed after the removal: recover, do not crash");
        assertEquals(0, r.cursor());
        IntPos first = items.get(0).pos();
        assertEquals(BlockSpec.AIR, b.w().blockAt(first), "the block was really removed");
        assertTrue(c.journal().at(JournalRecord.restoreIndex(0)).isPresent(), "the removal is journaled before the settle");
        assertFalse(b.registry().contains(first), "the position is no longer held");
        assertEquals(0, b.mats().count("minecraft:oak_planks"), "the refused apply gave nothing");
        StepReport again = ConstructionExecutor.run(c, r.cursor(), PLENTY);
        assertNull(again.pause());
        assertEquals(16, b.mats().count("minecraft:oak_planks"), "every owed refund is paid on the retry, once");
        assertEquals(9, b.mats().count("minecraft:cobblestone"));
    }
}
