package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.ReplacePolicy;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.PlacerId;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class JobServiceTest {
    static final UUID A = new UUID(0, 1);
    static final UUID B = new UUID(0, 2);
    static final double CALM = 20.0;
    static final int MAX_TICKS = 2_000;

    /** Hands out consecutive ticks, like the server does. */
    static final class Clock {
        long tick;

        /** One server tick; like the runtime, a checkpoint follows when a job waits for one. */
        List<JobUpdate> step(JobService s, FakeJobWorld w) {
            List<JobUpdate> out = s.tick(new TickInput(tick++, CALM), w);
            if (s.checkpointWanted()) {
                s.markDurable();
            }
            return out;
        }

        List<JobUpdate> stepAt(JobService s, FakeJobWorld w, double averageMspt) {
            List<JobUpdate> out = s.tick(new TickInput(tick++, averageMspt), w);
            if (s.checkpointWanted()) {
                s.markDurable();
            }
            return out;
        }

        List<JobUpdate> runUntil(JobService s, FakeJobWorld w, String jobId, Predicate<ConstructionJob> stop) {
            List<JobUpdate> all = new ArrayList<>();
            for (int i = 0; i < MAX_TICKS; i++) {
                all.addAll(step(s, w));
                if (stop.test(s.record(jobId).orElseThrow().job())) {
                    return all;
                }
            }
            throw new AssertionError("job " + jobId + " did not get there: " + s.status(jobId));
        }
    }

    static JobService service() {
        return new JobService(BudgetConfig.defaults(), new ClaimBook(ClaimBook.DEFAULT_MAX_CLAIMS_PER_OWNER),
                BuildingParts.registry(), true, false);
    }

    static PlacementManifest shifted(PlacementManifest m, int dx) {
        List<Placement> out = new ArrayList<>();
        for (Placement p : m.placements()) {
            out.add(new Placement(p.index(), p.pos().plus(dx, 0, 0), p.block(), p.blockEntityConfig(), p.partNodeId(), p.phase(),
                    p.placer(), p.verify(), p.replaces(), p.assemblyGroup()));
        }
        Box b = m.worldBounds();
        return TestManifests.of(new Box(b.minA() + dx, b.minB(), b.minC(), b.maxA() + dx, b.maxB(), b.maxC()), out);
    }

    static ConstructionJob approved(String id, UUID owner, PlacementManifest m, MaterialPolicy policy) {
        return ConstructionJob.create(id, owner, TestManifests.DIM, m.hash(), JobKind.BUILD, null, m.placements().size(),
                "claim-" + id, policy, 0L, List.of());
    }

    static void submit(JobService s, ConstructionJob j, PlacementManifest m) {
        s.admitApproved(j, m, Map.of(), JobProgram.build(m), m.worldBounds());
    }

    static Predicate<ConstructionJob> in(JobState state) {
        return j -> j.state() == state;
    }

    @Test
    void aHutIsBuiltAndVerifiedEndToEnd() {
        JobService s = service();
        Clock c = new Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        List<JobUpdate> ups = c.runUntil(s, w, "job-1", in(JobState.VERIFIED));
        for (Placement p : m.placements()) {
            assertEquals(p.block(), w.world.blockAt(p.pos()));
        }
        assertEquals(25, ups.stream().mapToInt(u -> u.touched().size()).sum(), "every placement is shown once");
        assertEquals("claim-job-1", s.claims().active().get(0).claimId(), "the claim outlives the job (D-23)");
        assertEquals(25, s.registry("claim-job-1").size());
        assertEquals(0, s.status("job-1").orElseThrow().repairRound());
    }

    @Test
    void overlappingApprovalsInOneTickAdmitOnlyTheFirst() {
        JobService s = service();
        Clock c = new Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        w.online.add(B);
        PlacementManifest m = TestManifests.smallHut();
        submit(s, approved("job-a", A, m, MaterialPolicy.CREATIVE_FREE), m);
        submit(s, approved("job-b", B, m, MaterialPolicy.CREATIVE_FREE), m);
        c.step(s, w);
        assertEquals(JobState.RUNNING, s.record("job-a").orElseThrow().job().state());
        ConstructionJob b = s.record("job-b").orElseThrow().job();
        assertEquals(JobState.CANCELLED, b.state());
        assertTrue(b.lastError().startsWith("E-CLAIM-OVERLAP:"), b.lastError());
    }

    @Test
    void theJobPausesWhileTheOwnerIsAwayAndResumesOnReturn() {
        JobService s = service();
        Clock c = new Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.step(s, w);
        w.online.remove(A);
        c.step(s, w);
        ConstructionJob paused = s.record("job-1").orElseThrow().job();
        assertEquals(PauseReason.OWNER_OFFLINE, paused.pauseReason());
        int cursor = paused.cursor();
        c.step(s, w);
        assertEquals(cursor, s.record("job-1").orElseThrow().job().cursor(), "nothing is placed while away");
        w.online.add(A);
        c.runUntil(s, w, "job-1", in(JobState.VERIFIED));
    }

    @Test
    void onlyTheOwnerOrAnOperatorMayCancelAndCancelKeepsWhatWasBuilt() {
        JobService s = service();
        Clock c = new Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.step(s, w);
        assertEquals(ControlResult.NOT_ALLOWED, s.cancel("job-1", B, false));
        assertEquals(ControlResult.NOT_FOUND, s.cancel("job-9", A, false));
        assertEquals(ControlResult.OK, s.cancel("job-1", B, true));
        assertEquals(JobState.CANCELLED, s.record("job-1").orElseThrow().job().state());
        assertEquals(ControlResult.WRONG_STATE, s.cancel("job-1", A, false));
        assertEquals(m.placements().get(0).block(), w.world.blockAt(m.placements().get(0).pos()), "cancel removes nothing");
        assertTrue(s.claims().find("claim-job-1").isPresent());
    }

    @Test
    void missingMaterialsWaitAndRetryEverySecond() {
        JobService s = service();
        Clock c = new Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        submit(s, approved("job-1", A, m, MaterialPolicy.SURVIVAL_CONSUME), m);
        List<JobUpdate> ups = c.runUntil(s, w, "job-1", in(JobState.PAUSED));
        assertEquals(PauseReason.MATERIALS_MISSING, s.record("job-1").orElseThrow().job().pauseReason());
        assertTrue(ups.stream().anyMatch(u -> !u.shortage().isEmpty()), "the owner is told what is missing");
        long pausedAt = c.tick - 1;
        w.inventories.get(A).with("minecraft:cobblestone", 9).with("minecraft:oak_planks", 16);
        while (c.tick < pausedAt + JobService.RETRY_INTERVAL_TICKS) {
            c.step(s, w);
            assertEquals(JobState.PAUSED, s.record("job-1").orElseThrow().job().state(), "no retry before one second");
        }
        c.step(s, w);
        assertEquals(JobState.RUNNING, s.record("job-1").orElseThrow().job().state(), "retried exactly one second later");
        c.runUntil(s, w, "job-1", in(JobState.VERIFIED));
        assertEquals(0, w.inventories.get(A).count("minecraft:oak_planks"));
    }

    @Test
    void aFifthJobWaitsAndIsShownAsServerBusy() {
        JobService s = service();
        Clock c = new Clock();
        FakeJobWorld w = new FakeJobWorld();
        PlacementManifest base = TestManifests.smallHut();
        for (int i = 0; i < 5; i++) {
            UUID owner = new UUID(1, i);
            w.online.add(owner);
            PlacementManifest m = shifted(base, 100 * i);
            submit(s, approved("job-" + i, owner, m, MaterialPolicy.CREATIVE_FREE), m);
        }
        c.step(s, w);
        long running = s.statuses().stream().filter(st -> st.state() == JobState.RUNNING).count();
        assertEquals(4, running);
        JobStatus waiting = s.status("job-4").orElseThrow();
        assertEquals(JobState.QUEUED, waiting.state());
        assertEquals(PauseReason.SERVER_BUSY, waiting.shownPause());
    }

    @Test
    void aSiteChangeStopsTheJobUntilTheOwnerChoosesToSkip() {
        JobService s = service();
        Clock c = new Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        IntPos blocked = m.placements().get(3).pos();
        w.world.setBlock(blocked, BlockSpec.of("minecraft:stone_bricks"));
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.runUntil(s, w, "job-1", in(JobState.PAUSED));
        assertEquals(PauseReason.SITE_CHANGED, s.record("job-1").orElseThrow().job().pauseReason());
        String error = s.record("job-1").orElseThrow().job().lastError();
        assertTrue(error.startsWith(IssueCode.E_SITE_CHANGED.label() + ":"), error);
        for (int i = 0; i < JobService.RETRY_INTERVAL_TICKS * 2; i++) {
            c.step(s, w);
        }
        assertEquals(JobState.PAUSED, s.record("job-1").orElseThrow().job().state(), "SITE_CHANGED waits for the owner");
        assertEquals(ControlResult.OK, s.resume("job-1", A, false, true));
        c.runUntil(s, w, "job-1", in(JobState.PARTIAL));
        assertEquals("minecraft:stone_bricks", w.world.blockAt(blocked).blockId());
        assertEquals(1, s.status("job-1").orElseThrow().conflicts());
        assertTrue(s.status("job-1").orElseThrow().lastError().contains("conflict=1"),
                "a position left to its player is a deviation: PARTIAL with the reason, not VERIFIED");
    }

    @Test
    void afterTheObstacleIsRemovedAResumeWithoutSkipBuildsThereAndDropsTheConflict() {
        JobService s = service();
        Clock c = new Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        IntPos blocked = m.placements().get(3).pos();
        w.world.setBlock(blocked, BlockSpec.of("minecraft:stone_bricks"));
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.runUntil(s, w, "job-1", in(JobState.PAUSED));
        w.world.setBlock(blocked, BlockSpec.AIR, CellTrait.REPLACEABLE);
        assertEquals(ControlResult.OK, s.resume("job-1", A, false, false));
        c.runUntil(s, w, "job-1", in(JobState.VERIFIED));
        assertEquals(m.placements().get(3).block(), w.world.blockAt(blocked));
        assertEquals(0, s.status("job-1").orElseThrow().conflicts());
        assertEquals(0, s.status("job-1").orElseThrow().skipped());
        assertEquals("", s.status("job-1").orElseThrow().lastError(), "a resolved E-SITE-CHANGED is not shown any more");
    }

    @Test
    void aSlowedRunningJobIsShownAsServerBusy() {
        JobService s = service();
        Clock c = new Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.step(s, w);
        assertEquals(null, s.status("job-1").orElseThrow().shownPause());
        c.stepAt(s, w, 50.0);
        JobStatus st = s.status("job-1").orElseThrow();
        assertEquals(JobState.RUNNING, st.state());
        assertTrue(s.slowed());
        assertEquals(PauseReason.SERVER_BUSY, st.shownPause());
    }

    @Test
    void anAllowanceStopsWhereThePaceChanges() {
        JobService s = service();
        Clock c = new Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        List<Placement> ps = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            ps.add(TestManifests.put(new IntPos(x, 64, 0), BlockSpec.of("minecraft:oak_planks"), "w", BuildPhase.STRUCTURE,
                    io.github.khayashi4337.micradrone.build.parts.VerifyMode.EXACT));
        }
        for (int x = 0; x < 3; x++) {
            ps.add(TestManifests.put(new IntPos(x, 65, 0), BlockSpec.of("minecraft:lantern"), "l", BuildPhase.DECORATION,
                    io.github.khayashi4337.micradrone.build.parts.VerifyMode.EXACT));
        }
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 3, 70, 1), ps);
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.step(s, w);
        assertEquals(3, s.record("job-1").orElseThrow().job().cursor(),
                "the fast structure allowance does not carry the drone-paced decoration along in the same tick");
        assertEquals(3, JobService.samePaceSteps(JobProgram.build(m), 0, 16, true));
        assertEquals(16, JobService.samePaceSteps(JobProgram.build(m), 0, 16, false), "without fast structure, one pace");
    }

    @Test
    void aJobOnAClaimMustFindItLiveHereAndItsOwners() {
        JobService s = service();
        Clock c = new Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        w.online.add(B);
        PlacementManifest m = TestManifests.smallHut();
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.runUntil(s, w, "job-1", in(JobState.VERIFIED));
        ConstructionJob foreign = ConstructionJob.create("job-2", B, TestManifests.DIM, m.hash(), JobKind.ROLLBACK, "job-1", 0,
                "claim-job-1", MaterialPolicy.CREATIVE_FREE, 0L, List.of());
        s.admitApproved(foreign, m, Map.of(), new JobProgram(List.of(), List.of()), m.worldBounds());
        ConstructionJob missing = ConstructionJob.create("job-3", A, TestManifests.DIM, m.hash(), JobKind.MODIFY, "job-1", 0,
                "claim-nope", MaterialPolicy.CREATIVE_FREE, 0L, List.of());
        s.admitApproved(missing, m, Map.of(), new JobProgram(List.of(), List.of()), m.worldBounds());
        c.step(s, w);
        for (String id : List.of("job-2", "job-3")) {
            ConstructionJob j = s.record(id).orElseThrow().job();
            assertEquals(JobState.CANCELLED, j.state(), id);
            assertTrue(j.lastError().startsWith(IssueCode.E_CLAIM_INVALID.label() + ":"), j.lastError());
        }
    }

    @Test
    void aModifyReachingFurtherGrowsItsClaim() {
        JobService s = service();
        Clock c = new Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.runUntil(s, w, "job-1", in(JobState.VERIFIED));
        PlacementManifest wider = shifted(m, 5);
        Box both = ClaimBook.union(m.worldBounds(), wider.worldBounds());
        ConstructionJob modify = ConstructionJob.create("job-2", A, TestManifests.DIM, wider.hash(), JobKind.MODIFY, "job-1",
                0, "claim-job-1", MaterialPolicy.CREATIVE_FREE, 0L, List.of());
        s.admitApproved(modify, wider, Map.of(), new JobProgram(List.of(), List.of()), both);
        c.step(s, w);
        assertEquals(both, s.claims().find("claim-job-1").orElseThrow().operatingBox(), "the claim now covers the new part");
    }

    @Test
    void aJobEndsOnlyAfterADurablePointCoversItsRuns() {
        JobService s = service();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        long tick = 0;
        for (int i = 0; i < MAX_TICKS && !s.checkpointWanted(); i++) {
            s.tick(new TickInput(tick++, CALM), w);
        }
        assertTrue(s.checkpointWanted(), "the build is placed and checked, and waits for the checkpoint");
        for (int i = 0; i < 20; i++) {
            s.tick(new TickInput(tick++, CALM), w);
        }
        assertEquals(JobState.VERIFYING, s.status("job-1").orElseThrow().state(), "not VERIFIED while its writes may be lost");
        s.markDurable();
        s.tick(new TickInput(tick, CALM), w);
        assertEquals(JobState.VERIFIED, s.status("job-1").orElseThrow().state());
    }

    @Test
    void aHandOverThatDoesNotFitWaitsAndGoesOnOnceThereIsRoom() {
        JobService s = service();
        Clock c = new Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        IntPos pos = new IntPos(0, 63, 0);
        Placement cut = new Placement(0, pos, BlockSpec.of("minecraft:cobblestone"), Map.of(), "found", BuildPhase.SITE_PREP,
                PlacerId.SIMPLE, VerifyMode.EXACT, ReplacePolicy.TERRAFORM, null);
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 2, 70, 1), List.of(cut));
        w.world.setBlock(pos, BlockSpec.of("minecraft:grass_block"), CellTrait.TERRAFORMABLE);
        FakeMaterials mats = new FakeMaterials().with("minecraft:cobblestone", 1);
        mats.room = 1;
        w.inventories.put(A, mats);
        submit(s, approved("job-1", A, m, MaterialPolicy.SURVIVAL_CONSUME), m);
        c.runUntil(s, w, "job-1", in(JobState.PAUSED).and(j -> j.pauseReason() == PauseReason.NO_ROOM));
        mats.room = FakeMaterials.UNLIMITED;
        c.runUntil(s, w, "job-1", in(JobState.VERIFIED));
        assertEquals(1, mats.count("minecraft:dirt"), "the cut ground handed over once there was room");
    }
}
