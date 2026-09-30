package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import org.junit.jupiter.api.Test;

import static io.github.khayashi4337.micradrone.construction.core.JobServiceTest.A;
import static io.github.khayashi4337.micradrone.construction.core.JobServiceTest.approved;
import static io.github.khayashi4337.micradrone.construction.core.JobServiceTest.in;
import static io.github.khayashi4337.micradrone.construction.core.JobServiceTest.service;
import static io.github.khayashi4337.micradrone.construction.core.JobServiceTest.submit;

class JobServiceL7Test {
    @Test
    void theL7RoundRepairsBrokenBlocksAndReportsReplacedOnes() {
        JobService s = service();
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        IntPos broken = m.placements().get(0).pos();
        IntPos swapped = m.placements().get(20).pos();
        boolean[] once = {false, false};
        w.world.afterWrite = (pos, block) -> {
            if (pos.equals(broken) && !once[0]) {
                once[0] = true;
                w.world.setBlock(pos, BlockSpec.AIR, CellTrait.REPLACEABLE);
            }
            if (pos.equals(swapped) && !once[1]) {
                once[1] = true;
                w.world.setBlock(pos, BlockSpec.of("minecraft:gold_block"));
            }
        };
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.runUntil(s, w, "job-1", in(JobState.PARTIAL));
        JobStatus st = s.status("job-1").orElseThrow();
        assertTrue(st.lastError().contains("conflict=1"), "the gold block is left to its player, so the job is PARTIAL");
        assertEquals(1, st.repairRound(), "one L7 round re-placed the broken block");
        assertEquals(m.placements().get(0).block(), w.world.blockAt(broken));
        assertEquals("minecraft:gold_block", w.world.blockAt(swapped).blockId(), "a swapped block is never overwritten");
        assertEquals(1, st.conflicts());
    }

    @Test
    void aPauseDuringRepairKeepsTheRepairQueueAndTheRound() {
        JobService s = service();
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        IntPos broken = m.placements().get(0).pos();
        boolean[] once = {false};
        w.world.afterWrite = (pos, block) -> {
            if (pos.equals(broken) && !once[0]) {
                once[0] = true;
                w.world.setBlock(pos, BlockSpec.AIR, CellTrait.REPLACEABLE);
            }
        };
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.runUntil(s, w, "job-1", in(JobState.REPAIRING));
        w.online.remove(A);
        c.step(s, w);
        assertEquals(PauseReason.OWNER_OFFLINE, s.record("job-1").orElseThrow().job().pauseReason());
        assertEquals(1, s.record("job-1").orElseThrow().repair().program().size(), "the repair queue survives the pause");
        w.online.add(A);
        c.runUntil(s, w, "job-1", in(JobState.VERIFIED));
        assertEquals(1, s.status("job-1").orElseThrow().repairRound(), "resuming does not spend another round");
        assertEquals(m.placements().get(0).block(), w.world.blockAt(broken));
    }

    @Test
    void aProtectedPositionEndsInPartialWithTheReason() {
        JobService s = service();
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        w.world.deny(m.placements().get(5).pos());
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.runUntil(s, w, "job-1", in(JobState.PARTIAL));
        JobRecord r = s.record("job-1").orElseThrow();
        assertEquals(1, r.remaining().size());
        assertTrue(r.job().lastError().contains("blocked=1"), r.job().lastError());
        assertEquals(0, r.job().repairRound(), "nothing to retry: protection is not L7's to fix");
    }

    @Test
    void aBlockThatKeepsBreakingGivesUpAfterThreeRounds() {
        JobService s = service();
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        IntPos flaky = m.placements().get(0).pos();
        w.world.afterWrite = (pos, block) -> {
            if (pos.equals(flaky) && !block.isAir()) {
                w.world.setBlock(pos, BlockSpec.AIR, CellTrait.REPLACEABLE);
            }
        };
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.runUntil(s, w, "job-1", in(JobState.PARTIAL));
        JobRecord r = s.record("job-1").orElseThrow();
        assertEquals(ConstructionJob.MAX_REPAIR_ROUNDS, r.job().repairRound());
        assertTrue(r.job().lastError().contains("missing=1"), r.job().lastError());
    }

    @Test
    void anUnloadedChunkDuringVerificationPausesAndIsReadAgain() {
        JobService s = service();
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        IntPos last = m.placements().get(24).pos();
        w.world.afterWrite = (pos, block) -> {
            if (pos.equals(last)) {
                w.world.unload(pos);
            }
        };
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.runUntil(s, w, "job-1", in(JobState.PAUSED));
        assertEquals(PauseReason.CHUNK_UNLOADED, s.record("job-1").orElseThrow().job().pauseReason());
        w.world.afterWrite = (pos, block) -> {
        };
        w.world.load(last);
        c.runUntil(s, w, "job-1", in(JobState.VERIFIED));
    }
}
