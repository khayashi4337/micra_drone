package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.ReplacePolicy;
import io.github.khayashi4337.micradrone.build.compile.SiteSurvey;
import io.github.khayashi4337.micradrone.build.compile.TerrainPrep;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A survival build over grass (cut ground is handed over as dirt) and its rollback, stopped by a crash at every single
 * step that changes something (a log flush, an item move, an owner save, a world write). The disk lags the live state
 * the way a server's does: an autosave writes only some chunks, the job files and the owner (never a durable point); a
 * checkpoint (a flushed save) writes everything and is the only durable point; the owner picks apples up and drops them
 * between runs. After a crash the jobs are recovered from the log; an ambiguous job is answered the way an owner who
 * looked would answer (the truth of the disk). Nothing may be charged, given or returned twice, and nothing may be lost.
 */
class CrashRecoveryTest {
    private static final int ALLOWANCE = 4;
    private static final Box SITE = new Box(-2, 55, -2, 4, 70, 4);
    private static final int SURFACE = 63;
    private static final String COBBLE = "minecraft:cobblestone";
    private static final String PLANKS = "minecraft:oak_planks";
    private static final String DIRT = "minecraft:dirt";
    private static final String APPLE = "minecraft:apple";
    private static final BlockSpec GRASS = BlockSpec.of("minecraft:grass_block");
    private static final int AUTOSAVE_EVERY = 2;
    private static final int CHECKPOINT_EVERY = 5;

    static final class Crash extends RuntimeException {
    }

    static final class Crasher implements Runnable {
        int count;
        int at = Integer.MAX_VALUE;

        @Override
        public void run() {
            if (++count == at) {
                throw new Crash();
            }
        }
    }

    /** A world whose chunks reach the disk only when saved, some chunks at a time. */
    static final class DiskWorld implements WorldPort {
        final Map<IntPos, WorldCell> live = new HashMap<>();
        final Map<IntPos, WorldCell> disk = new HashMap<>();
        Runnable hook = () -> {
        };

        void set(IntPos p, WorldCell c) {
            live.put(p, c);
            disk.put(p, c);
        }

        @Override
        public WorldCell read(IntPos pos) {
            return live.getOrDefault(pos, WorldCell.of(BlockSpec.AIR, CellTrait.REPLACEABLE));
        }

        @Override
        public PlaceResult place(IntPos pos, BlockSpec block, Map<String, String> blockEntityConfig, UUID actor) {
            live.put(pos, WorldCell.of(block));
            hook.run();
            return PlaceResult.PLACED;
        }

        @Override
        public PlaceResult restore(IntPos pos, BlockSpec block, UUID actor, boolean dropContentsFirst) {
            live.put(pos, block.isAir() ? WorldCell.of(BlockSpec.AIR, CellTrait.REPLACEABLE)
                    : WorldCell.of(block, CellTrait.TERRAFORMABLE));
            hook.run();
            return PlaceResult.PLACED;
        }

        @Override
        public void settle(List<IntPos> positions) {
        }

        /** An autosave: the chunks of one parity reach the disk (the rest may still be queued when the crash comes). */
        void saveSome(int parity) {
            for (Map.Entry<IntPos, WorldCell> e : live.entrySet()) {
                if (Math.floorMod(e.getKey().x() + e.getKey().z(), 2) == parity) {
                    disk.put(e.getKey(), e.getValue());
                }
            }
            disk.keySet().removeIf(p -> !live.containsKey(p) && Math.floorMod(p.x() + p.z(), 2) == parity);
        }

        void saveAll() {
            disk.clear();
            disk.putAll(live);
        }

        DiskWorld restart() {
            DiskWorld w = new DiskWorld();
            disk.forEach(w::set);
            return w;
        }
    }

    /** The job files as the last save wrote them. */
    record Files(Journal buildJournal, Journal rollbackJournal, LedgerBook ledgers, PlacedRegistry registry, int buildCursor,
                 int rollbackCursor, boolean rollbackKnown) {
    }

    /** Everything a server holds for the two jobs. */
    static final class Server {
        final PlacementManifest manifest;
        DiskWorld world;
        FakeMaterials mats;
        WriteAheadLog.MemorySink sink = new WriteAheadLog.MemorySink();
        WriteAheadLog wal = new WriteAheadLog(sink, 0L);
        Journal buildJournal = new Journal();
        Journal rollbackJournal = new Journal();
        LedgerBook ledgers = new LedgerBook();
        PlacedRegistry registry = new PlacedRegistry("claim-job-1");
        JobProgram rollback;
        int buildCursor;
        int rollbackCursor;
        boolean rollbackStarted;
        long chestSavedUpTo;
        int runs;
        Files files;

        Server(PlacementManifest manifest, DiskWorld world, FakeMaterials mats) {
            this.manifest = manifest;
            this.world = world;
            this.mats = mats;
        }
    }

    private static Journal copy(Journal j) {
        Journal out = new Journal();
        j.records().forEach(out::record);
        return out;
    }

    private static LedgerBook copy(LedgerBook b) {
        LedgerBook out = new LedgerBook();
        b.all().forEach((id, l) -> {
            MaterialLedger c = out.of(id);
            l.consumedView().forEach((k, v) -> c.replay(MaterialOp.CHARGE, k, v));
            l.returnedView().forEach((k, v) -> c.replay(MaterialOp.RETURN, k, v));
            l.yieldedView().forEach((k, v) -> c.replay(MaterialOp.YIELD, k, v));
            l.reclaimedView().forEach((k, v) -> c.replay(MaterialOp.RECLAIM, k, v));
            if (l.appliedRun() != MaterialLedger.NO_RUN) {
                c.markAppliedRun(l.appliedRun());
            }
        });
        return out;
    }

    private static PlacedRegistry copy(PlacedRegistry r) {
        PlacedRegistry out = new PlacedRegistry(r.claimId());
        out.putAll(r.placed());
        return out;
    }

    /** The job files are written (at every save, the autosave's too). */
    private static void writeFiles(Server s) {
        s.files = new Files(copy(s.buildJournal), copy(s.rollbackJournal), copy(s.ledgers), copy(s.registry), s.buildCursor,
                s.rollbackCursor, s.rollbackStarted);
    }

    private static void autosave(Server s) {
        s.world.saveSome(s.runs / AUTOSAVE_EVERY % 2);
        if (s.runs / AUTOSAVE_EVERY % 2 == 0) {
            s.mats.saveChests();
            s.chestSavedUpTo = s.wal.lastRun();
        }
        s.mats.saveOwner();
        writeFiles(s);
    }

    /** A flushed save of the whole server: the only durable point. */
    private static void checkpoint(Server s) {
        s.world.saveAll();
        s.mats.saveChests();
        s.chestSavedUpTo = s.wal.lastRun();
        writeFiles(s);
        s.wal.markDurable(s.wal.lastRun());
    }

    private static PlacementManifest overGrass() {
        return TerrainPrep.apply(TestManifests.smallHut(), SiteSurvey.flat(TestManifests.DIM, SITE, SURFACE,
                GRASS.blockId())).manifest();
    }

    private static Server fresh(boolean chest) {
        PlacementManifest m = overGrass();
        DiskWorld w = new DiskWorld();
        for (Placement p : m.placements()) {
            if (p.replaces() instanceof ReplacePolicy.Terraform) {
                w.set(p.pos(), WorldCell.of(GRASS, CellTrait.TERRAFORMABLE));
            }
        }
        FakeMaterials mats = chest
                ? new FakeMaterials().with(COBBLE, 4).with(PLANKS, 6).withChest(COBBLE, 5).withChest(PLANKS, 10)
                : new FakeMaterials().with(COBBLE, 9).with(PLANKS, 16);
        Server s = new Server(m, w, mats);
        writeFiles(s);
        return s;
    }

    private static JobProgram buildProgram(Server s) {
        return JobProgram.build(s.manifest);
    }

    private static JobProgram rollbackProgram(Server s) {
        if (s.rollback == null) {
            List<RestoreItem> items = new ArrayList<>();
            s.registry.placed().forEach((pos, e) -> items.add(new RestoreItem(pos, e.placed(), Set.of(), e.before(), e.jobId(),
                    e.placementIndex(), false, e.earlierKeys())));
            items.sort(Attachments.REMOVAL_ORDER);
            s.rollback = new JobProgram(items, List.of());
        }
        return s.rollback;
    }

    private static ExecutionContext buildCtx(Server s) {
        ConstructionJob job = ConstructionExecutorTest.running(MaterialPolicy.SURVIVAL_CONSUME, s.manifest.placements().size());
        return new ExecutionContext(job, buildProgram(s), s.world, s.mats, s.buildJournal, s.ledgers, s.registry, new JobOutcome(),
                false, s.wal);
    }

    private static ExecutionContext rollbackCtx(Server s) {
        ConstructionJob job = ConstructionJob.create("job-2", ConstructionJobTest.OWNER, TestManifests.DIM, "h", JobKind.ROLLBACK,
                "job-1", rollbackProgram(s).size(), "claim-job-1", MaterialPolicy.SURVIVAL_CONSUME, 0L, List.of())
                .on(JobEvent.ADMITTED).on(JobEvent.START);
        return new ExecutionContext(job, rollbackProgram(s), s.world, s.mats, s.rollbackJournal, s.ledgers, s.registry,
                new JobOutcome(), false, s.wal);
    }

    /** A new job's files are written the moment it is created, before its first run (so no run is ever of an unknown job). */
    private static void startRollback(Server s) {
        s.rollbackStarted = true;
        rollbackProgram(s);
        writeFiles(s);
    }

    /** Runs a job to its end in small runs, with the owner's pickups and the server's saves between the runs. */
    private static void finish(Server s, ExecutionContext ctx, boolean build) {
        int cursor = build ? s.buildCursor : s.rollbackCursor;
        while (cursor < ctx.program().size()) {
            StepReport r = ConstructionExecutor.run(ctx, cursor, ALLOWANCE);
            assertEquals(null, r.pause(), "the materials were enough: " + r);
            cursor = r.cursor();
            if (build) {
                s.buildCursor = cursor;
            } else {
                s.rollbackCursor = cursor;
            }
            s.runs++;
            s.mats.ownerChanges(APPLE, s.runs % 3 == 0 ? -1 : 1);
            if (s.runs % CHECKPOINT_EVERY == 0) {
                checkpoint(s);
            } else if (s.runs % AUTOSAVE_EVERY == 0) {
                autosave(s);
            }
        }
        // a job finishes only after a durable point covers its runs (JobService waits for the checkpoint)
        checkpoint(s);
    }

    /**
     * A restart: the disk only. The files, the world, the owner and the chest as last saved, the whole durable log.
     * Each job the files know is recovered; an ambiguous one is answered like an owner who looked: a run cut short left
     * nothing, a chest take happened only if the chest's chunk was saved after it. Then the start-up checkpoint.
     */
    private static Server restart(Server before) {
        Server s = new Server(before.manifest, before.world.restart(), before.mats.restart());
        s.sink.durable.addAll(before.sink.durable);
        s.wal = WriteAheadLog.open(s.sink);
        Files f = before.files;
        s.buildJournal = copy(f.buildJournal());
        s.rollbackJournal = copy(f.rollbackJournal());
        s.ledgers = copy(f.ledgers());
        s.registry = copy(f.registry());
        s.rollback = f.rollbackKnown() ? before.rollback : null;
        s.rollbackStarted = f.rollbackKnown();
        List<WalEntry> log = s.wal.durable();
        long chestSaved = before.chestSavedUpTo;
        java.util.function.LongPredicate truth = run -> {
            WalEntry.RunEnd end = log.stream().filter(e -> e instanceof WalEntry.RunEnd re && re.run() == run)
                    .map(e -> (WalEntry.RunEnd) e).findFirst().orElse(null);
            return end != null && chestSaved >= run;
        };
        WalRecovery.Result build = WalRecovery.recover("job-1", log, buildProgram(s), s.buildJournal, s.registry, s.ledgers,
                s.world, s.mats.durableTx(), truth);
        s.buildCursor = Math.min(f.buildCursor(), build.rewindTo());
        if (s.rollbackStarted) {
            WalRecovery.Result rb = WalRecovery.recover("job-2", log, rollbackProgram(s), s.rollbackJournal, s.registry,
                    s.ledgers, s.world, s.mats.durableTx(), truth);
            s.rollbackCursor = Math.min(f.rollbackCursor(), rb.rewindTo());
        }
        // the start-up checkpoint: the recovered files, and the log starts over
        checkpoint(s);
        return s;
    }

    private static int bom(PlacementManifest m, String item) {
        return m.bom().getOrDefault(item, 0);
    }

    /** The block each position ends with (a cut of the ground can come before the foundation at the same position). */
    private static Map<IntPos, BlockSpec> finalBlocks(PlacementManifest m) {
        Map<IntPos, BlockSpec> out = new HashMap<>();
        m.placements().forEach(p -> out.put(p.pos(), p.block()));
        return out;
    }

    /** The block each position had before the build: grass where the first placement there cut the ground. */
    private static Map<IntPos, BlockSpec> groundBlocks(PlacementManifest m) {
        Map<IntPos, BlockSpec> out = new HashMap<>();
        m.placements().forEach(p -> out.putIfAbsent(p.pos(), p.replaces() instanceof ReplacePolicy.Terraform ? GRASS : BlockSpec.AIR));
        return out;
    }

    private static void assertBuilt(Server s, String when) {
        finalBlocks(s.manifest).forEach((pos, block) -> assertEquals(block, s.world.read(pos).block(), when + ": " + pos));
        assertEquals(9 - bom(s.manifest, COBBLE), s.mats.total(COBBLE), when + ": cobblestone charged exactly once");
        assertEquals(16 - bom(s.manifest, PLANKS), s.mats.total(PLANKS), when + ": planks charged exactly once");
        assertEquals(9, s.mats.total(DIRT), when + ": the 9 cut grass blocks handed over exactly once");
    }

    private static void assertRolledBack(Server s, String when) {
        groundBlocks(s.manifest).forEach((pos, block) -> assertEquals(block, s.world.read(pos).block(), when + ": " + pos));
        assertEquals(9, s.mats.total(COBBLE), when + ": every charge returned exactly once");
        assertEquals(16, s.mats.total(PLANKS), when);
        assertEquals(0, s.mats.total(DIRT), when + ": the ground taken back exactly once");
    }

    @Test
    void aBuildAndItsRollbackSurviveACrashAtEveryStep() {
        int trials = 0;
        for (boolean chest : new boolean[]{false, true}) {
            for (int at = 1; ; at++) {
                Server s = fresh(chest);
                Crasher crasher = new Crasher();
                crasher.at = at;
                s.sink.hook = crasher;
                s.mats.hook = crasher;
                s.world.hook = crasher;
                String when = "chest=" + chest + " crash at step " + at;
                boolean crashed = false;
                boolean buildDone = false;
                try {
                    finish(s, buildCtx(s), true);
                    buildDone = true;
                    assertBuilt(s, when + " (no crash yet)");
                    startRollback(s);
                    finish(s, rollbackCtx(s), false);
                } catch (Crash c) {
                    crashed = true;
                }
                if (!crashed) {
                    assertRolledBack(s, when);
                    break;
                }
                trials++;
                Server back = restart(s);
                if (!buildDone) {
                    finish(back, buildCtx(back), true);
                    assertBuilt(back, when + " (crash in the build)");
                }
                if (!back.rollbackStarted) {
                    startRollback(back);
                }
                finish(back, rollbackCtx(back), false);
                assertRolledBack(back, when + (buildDone ? " (crash in the rollback)" : ""));
            }
        }
        assertTrue(trials > 100, "every step was a crash point: " + trials);
    }
}
