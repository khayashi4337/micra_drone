package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JobServiceRecoverTest {
    @Test
    void aBrokenJobWaitsUntilSomeoneChoosesAndNeverRunsSilently() {
        JobService s = JobServiceTest.service();
        JobServiceTest.Clock c = new JobServiceTest.Clock();
        FakeJobWorld w = new FakeJobWorld();
        w.online.add(JobServiceTest.A);
        PlacementManifest m = TestManifests.smallHut();
        ConstructionJob paused = JobServiceTest.approved("job-1", JobServiceTest.A, m, MaterialPolicy.CREATIVE_FREE)
                .on(JobEvent.ADMITTED).on(JobEvent.START).withCursor(10).paused(PauseReason.RECOVERY_NEEDED);
        s.claims().reserve("claim-job-1", JobServiceTest.A, TestManifests.DIM, m.worldBounds(), m.worldBounds(), 0L);
        s.addBroken(paused, m, Map.of(), m.worldBounds(), List.of("journal: missing"));
        for (int i = 0; i < JobService.RETRY_INTERVAL_TICKS * 3; i++) {
            c.step(s, w);
        }
        assertEquals(PauseReason.RECOVERY_NEEDED, s.status("job-1").orElseThrow().shownPause());
        assertEquals(0, w.world.log.size(), "nothing was placed");
        assertEquals(ControlResult.WRONG_STATE, s.resume("job-1", JobServiceTest.A, false, false), "resume is not the way out");
        assertEquals(ControlResult.WRONG_STATE, s.recover("job-1", JobServiceTest.A, false, RecoveryChoice.ADOPT, "job-2", c.tick, w),
                "adopt and discard answer an undecided log; this job's files are broken");
        assertEquals(ControlResult.OK, s.recover("job-1", JobServiceTest.A, false, RecoveryChoice.REPAIR, "job-2", c.tick, w));
        assertEquals(JobState.FAILED, s.status("job-1").orElseThrow().state());
        c.runUntil(s, w, "job-2", JobServiceTest.in(JobState.VERIFIED));
        assertEquals(JobKind.REPAIR, s.status("job-2").orElseThrow().kind());
    }
}
