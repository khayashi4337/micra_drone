package io.github.khayashi4337.micradrone.construction.core;

import static io.github.khayashi4337.micradrone.construction.core.JobServiceTest.A;
import static io.github.khayashi4337.micradrone.construction.core.JobServiceTest.B;
import static io.github.khayashi4337.micradrone.construction.core.JobServiceTest.approved;
import static io.github.khayashi4337.micradrone.construction.core.JobServiceTest.in;
import static io.github.khayashi4337.micradrone.construction.core.JobServiceTest.service;
import static io.github.khayashi4337.micradrone.construction.core.JobServiceTest.submit;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import org.junit.jupiter.api.Test;

class JobServiceVerifyTest {
    @Test
    void aVerifyJobRepairsAFinishedBuildWithoutApprovalAndLeavesSwappedBlocks() {
        JobService s = service();
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        PlacementManifest m = TestManifests.smallHut();
        submit(s, approved("job-1", A, m, MaterialPolicy.CREATIVE_FREE), m);
        c.runUntil(s, w, "job-1", in(JobState.VERIFIED));
        IntPos broken = m.placements().get(10).pos();
        IntPos swapped = m.placements().get(11).pos();
        w.world.setBlock(broken, BlockSpec.AIR, CellTrait.REPLACEABLE);
        w.world.setBlock(swapped, BlockSpec.of("minecraft:gold_block"));
        assertEquals(ControlResult.NOT_ALLOWED, s.beginVerify("job-1", B, false, "job-2", c.tick));
        assertEquals(ControlResult.OK, s.beginVerify("job-1", A, false, "job-2", c.tick));
        c.runUntil(s, w, "job-2", in(JobState.PARTIAL));
        JobStatus st = s.status("job-2").orElseThrow();
        assertEquals(JobKind.REPAIR, st.kind());
        assertEquals(1, st.repairRound());
        assertEquals(1, st.conflicts());
        assertEquals(m.placements().get(10).block(), w.world.blockAt(broken));
        assertEquals("minecraft:gold_block", w.world.blockAt(swapped).blockId());
        assertEquals(ControlResult.NOT_FOUND, s.beginVerify("job-9", A, false, "job-3", c.tick));
        assertEquals(25, s.record("job-1").orElseThrow().journal().size(), "the parent's journal is not written by the repair");
        assertEquals(1, s.record("job-2").orElseThrow().journal().size(), "the repair's own record of its one re-placement");
    }

    @Test
    void aSurvivalRepairChargesOnlyTheBlockItPlacesAgain() {
        JobService s = service();
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(A);
        w.inventories.put(A, new FakeMaterials().with("minecraft:cobblestone", 9).with("minecraft:oak_planks", 17));
        PlacementManifest m = TestManifests.smallHut();
        submit(s, approved("job-1", A, m, MaterialPolicy.SURVIVAL_CONSUME), m);
        c.runUntil(s, w, "job-1", in(JobState.VERIFIED));
        w.world.setBlock(m.placements().get(10).pos(), BlockSpec.AIR, CellTrait.REPLACEABLE);
        assertEquals(ControlResult.OK, s.beginVerify("job-1", A, false, "job-2", c.tick));
        c.runUntil(s, w, "job-2", in(JobState.VERIFIED));
        assertEquals(0, w.inventories.get(A).count("minecraft:oak_planks"), "one plank for the one broken wall block");
        assertEquals(0, w.inventories.get(A).count("minecraft:cobblestone"), "nothing else is charged again");
    }
}
