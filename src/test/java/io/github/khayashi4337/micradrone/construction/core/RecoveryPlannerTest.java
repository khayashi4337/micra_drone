package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecoveryPlannerTest {
    private static JobLoad.Loaded loaded(JobRecord r) {
        return new JobLoad.Loaded(r, new MaterialLedger());
    }

    @Test
    void aRunningJobComesBackPausedUntilItsOwnerIsHereAndAdoptsFirst() {
        JobRecord r = JobFilesTest.builtHalfway(new FakeJobWorld());
        RecoveryDecision d = RecoveryPlanner.decide(r.job(), loaded(r));
        assertEquals(JobState.PAUSED, d.job().state());
        assertEquals(PauseReason.OWNER_OFFLINE, d.job().pauseReason());
        assertTrue(d.fromLog(), "the log may hold what the files do not (async chunk saves)");
    }

    @Test
    void aJournalBehindTheCursorIsNotSettledUntilAdopted() {
        JobRecord r = JobFilesTest.builtHalfway(new FakeJobWorld());
        ConstructionJob ahead = r.job().withCursor(r.job().cursor() + 3);
        assertFalse(RecoveryPlanner.settledUpTo(r, ahead.cursor()));
        assertTrue(RecoveryPlanner.decide(ahead, loaded(r)).fromLog(), "JobService recovers from the log, then checks again");
    }

    @Test
    void aJournalAheadOfTheCursorIsNormal() {
        JobRecord r = JobFilesTest.builtHalfway(new FakeJobWorld());
        ConstructionJob behind = r.job().withCursor(Math.max(0, r.job().cursor() - 3));
        assertEquals(PauseReason.OWNER_OFFLINE, RecoveryPlanner.decide(behind, loaded(r)).job().pauseReason());
        assertTrue(RecoveryPlanner.settledUpTo(r, behind.cursor()));
    }

    @Test
    void brokenFilesPauseALiveJobAndLeaveAFinishedOneAsItIs() {
        JobRecord r = JobFilesTest.builtHalfway(new FakeJobWorld());
        JobLoad broken = new JobLoad.Broken(r.manifest(), Map.of(), List.of("journal: check value mismatch"));
        assertEquals(PauseReason.RECOVERY_NEEDED, RecoveryPlanner.decide(r.job(), broken).job().pauseReason());
        assertFalse(RecoveryPlanner.decide(r.job(), broken).fromLog());
        PlacementManifest m = TestManifests.smallHut();
        ConstructionJob done = ConstructionJob.create("job-2", JobServiceTest.A, TestManifests.DIM, m.hash(), JobKind.BUILD, null, 25,
                "claim-job-2", MaterialPolicy.CREATIVE_FREE, 0L, List.of()).on(JobEvent.ADMITTED).on(JobEvent.START)
                .withCursor(25).on(JobEvent.PLACED_ALL).on(JobEvent.CLEAN);
        assertEquals(JobState.VERIFIED, RecoveryPlanner.decide(done, broken).job().state());
        JobRecord finished = new JobRecord(done, m, Map.of(), JobProgram.build(m), new Journal(), new JobOutcome(), m.worldBounds());
        assertTrue(RecoveryPlanner.decide(done, loaded(finished)).fromLog(),
                "a finished job too: the world may have lost its last writes");
    }
}
