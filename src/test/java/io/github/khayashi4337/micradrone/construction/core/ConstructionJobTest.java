package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConstructionJobTest {
    static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    static ConstructionJob job() {
        return ConstructionJob.create("job-1", OWNER, "minecraft:overworld", "abc", JobKind.BUILD, null, 238,
                "claim-job-1", MaterialPolicy.CREATIVE_FREE, 100L, List.of("W-UNMODELED:x"));
    }

    @Test
    void createStartsPendingWithFilesUnderTheJobFolder() {
        ConstructionJob j = job();
        assertEquals(JobState.PENDING_APPROVAL, j.state());
        assertNull(j.pauseReason());
        assertEquals(0, j.cursor());
        assertEquals(238, j.total());
        assertEquals("jobs/job-1/journal.bin", j.journalFile());
        assertEquals("jobs/job-1/ledger.bin", j.ledgerFile());
        assertEquals("", j.lastError());
        assertEquals(List.of("W-UNMODELED:x"), j.acceptedRiskIds());
        assertEquals(ConstructionJob.SCHEMA_VERSION, j.schemaVersion());
    }

    @Test
    void eventsGoThroughTheStateMachineAndPauseCarriesItsReason() {
        ConstructionJob j = job().on(JobEvent.ADMITTED).on(JobEvent.START);
        assertEquals(JobState.RUNNING, j.state());
        ConstructionJob p = j.paused(PauseReason.CHUNK_UNLOADED);
        assertEquals(JobState.PAUSED, p.state());
        assertEquals(PauseReason.CHUNK_UNLOADED, p.pauseReason());
        ConstructionJob r = p.on(JobEvent.RESUME);
        assertEquals(JobState.QUEUED, r.state());
        assertNull(r.pauseReason(), "leaving PAUSED clears the reason");
        assertThrows(IllegalArgumentException.class, () -> j.on(JobEvent.PAUSE), "use paused(reason) for PAUSE");
        assertThrows(IllegalStateException.class, () -> job().on(JobEvent.START), "pending jobs cannot start");
    }

    @Test
    void invariantsAreChecked() {
        ConstructionJob j = job();
        assertThrows(IllegalArgumentException.class, () -> j.withCursor(239));
        assertThrows(IllegalArgumentException.class, () -> j.withCursor(-1));
        assertEquals(238, j.withCursor(238).cursor());
        assertThrows(IllegalArgumentException.class, () -> j.withRepairRound(ConstructionJob.MAX_REPAIR_ROUNDS + 1));
        assertThrows(IllegalArgumentException.class, () -> ConstructionJob.create("../evil", OWNER, "minecraft:overworld",
                "abc", JobKind.BUILD, null, 1, "c", MaterialPolicy.CREATIVE_FREE, 0L, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ConstructionJob(1, "job-2", OWNER, "minecraft:overworld", "abc",
                JobKind.BUILD, null, JobState.PAUSED, null, 0, 1, 0, "c", MaterialPolicy.CREATIVE_FREE,
                "jobs/job-2/journal.bin", "jobs/job-2/ledger.bin", "", 0L, List.of()), "PAUSED needs a reason");
        assertThrows(IllegalArgumentException.class, () -> new ConstructionJob(1, "job-2", OWNER, "minecraft:overworld", "abc",
                JobKind.BUILD, null, JobState.RUNNING, PauseReason.USER, 0, 1, 0, "c", MaterialPolicy.CREATIVE_FREE,
                "jobs/job-2/journal.bin", "jobs/job-2/ledger.bin", "", 0L, List.of()), "only PAUSED has a reason");
    }

    @Test
    void withersKeepEverythingElse() {
        ConstructionJob j = job();
        ConstructionJob k = j.withCursor(5).withRepairRound(2).withLastError("x").withTotal(300).withClaimId("claim-9");
        assertEquals(5, k.cursor());
        assertEquals(2, k.repairRound());
        assertEquals("x", k.lastError());
        assertEquals(300, k.total());
        assertEquals("claim-9", k.claimId());
        assertEquals(j.jobId(), k.jobId());
        assertEquals(j.createdTick(), k.createdTick());
        assertEquals(3, j.withCursor(5).withTotal(3).cursor(), "shrinking the total pulls the cursor in");
    }
}
