package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import org.junit.jupiter.api.Test;

class JobServiceRollbackTest {
    @Test
    void aRollbackThatLeavesAPlayersBlockIsPartialAndKeepsTheClaimUntilTheSiteIsClean() {
        JobService s = JobServiceTest.service();
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(JobServiceTest.A);
        PlacementManifest m = TestManifests.smallHut();
        w.inventories.put(JobServiceTest.A, new FakeMaterials().with("minecraft:cobblestone", 9).with("minecraft:oak_planks", 16));
        JobServiceTest.submit(s, JobServiceTest.approved("job-1", JobServiceTest.A, m, MaterialPolicy.SURVIVAL_CONSUME), m);
        c.runUntil(s, w, "job-1", JobServiceTest.in(JobState.VERIFIED));
        IntPos swapped = m.placements().get(12).pos();
        w.world.setBlock(swapped, BlockSpec.of("minecraft:gold_block"));
        assertEquals(ControlResult.NOT_ALLOWED, s.rollback("claim-job-1", JobServiceTest.B, false, "job-2", c.tick));
        assertEquals(ControlResult.OK, s.rollback("claim-job-1", JobServiceTest.A, false, "job-2", c.tick));
        c.runUntil(s, w, "job-2", JobServiceTest.in(JobState.PARTIAL));
        for (Placement p : m.placements()) {
            if (!p.pos().equals(swapped)) {
                assertEquals(BlockSpec.AIR, w.world.blockAt(p.pos()));
            }
        }
        assertEquals("minecraft:gold_block", w.world.blockAt(swapped).blockId(), "a player's change is left alone");
        assertEquals(1, s.status("job-2").orElseThrow().conflicts());
        assertEquals(JobState.VERIFIED, s.status("job-1").orElseThrow().state(), "not rolled back while a block is left");
        assertFalse(s.claims().find("claim-job-1").orElseThrow().released(), "the claim stays while the site is not clean");
        assertTrue(s.registries().get("claim-job-1").contains(swapped), "the position left alone is still on the list");
        FakeMaterials inv = w.inventories.get(JobServiceTest.A);
        assertEquals(9, inv.count("minecraft:cobblestone"));
        assertEquals(15, inv.count("minecraft:oak_planks"), "the plank under the gold block was not taken back");
        // the player clears the gold block: the ground is as before the build, so a second rollback finishes
        w.world.setBlock(swapped, BlockSpec.AIR);
        assertEquals(ControlResult.OK, s.rollback("claim-job-1", JobServiceTest.A, false, "job-3", c.tick));
        c.runUntil(s, w, "job-3", JobServiceTest.in(JobState.VERIFIED));
        assertEquals(JobState.ROLLED_BACK, s.status("job-1").orElseThrow().state());
        assertTrue(s.claims().find("claim-job-1").orElseThrow().released());
        assertEquals(15, inv.count("minecraft:oak_planks"), "a block the player removed is not refunded");
        assertEquals(ControlResult.WRONG_STATE, s.rollback("claim-job-1", JobServiceTest.A, false, "job-4", c.tick),
                "a released claim cannot be rolled back twice");
    }

    @Test
    void aCleanRollbackReleasesTheClaimAndReturnsWhatWasConsumedOnce() {
        JobService s = JobServiceTest.service();
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(JobServiceTest.A);
        PlacementManifest m = TestManifests.smallHut();
        w.inventories.put(JobServiceTest.A, new FakeMaterials().with("minecraft:cobblestone", 9).with("minecraft:oak_planks", 16));
        JobServiceTest.submit(s, JobServiceTest.approved("job-1", JobServiceTest.A, m, MaterialPolicy.SURVIVAL_CONSUME), m);
        c.runUntil(s, w, "job-1", JobServiceTest.in(JobState.VERIFIED));
        assertEquals(ControlResult.OK, s.rollback("claim-job-1", JobServiceTest.A, false, "job-2", c.tick));
        c.runUntil(s, w, "job-2", JobServiceTest.in(JobState.VERIFIED));
        for (Placement p : m.placements()) {
            assertEquals(BlockSpec.AIR, w.world.blockAt(p.pos()));
        }
        assertEquals(JobState.ROLLED_BACK, s.status("job-1").orElseThrow().state());
        assertTrue(s.claims().find("claim-job-1").orElseThrow().released());
        assertFalse(s.registries().containsKey("claim-job-1"));
        FakeMaterials inv = w.inventories.get(JobServiceTest.A);
        assertEquals(9, inv.count("minecraft:cobblestone"));
        assertEquals(16, inv.count("minecraft:oak_planks"));
    }
}
