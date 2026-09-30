package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.model.Box;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrphanSweepTest {
    private static ConstructionJob job(String id, String claim, String hash, JobState end) {
        ConstructionJob j = ConstructionJob.create(id, new UUID(0, 1), "minecraft:overworld", hash, JobKind.BUILD, null, 0, claim,
                MaterialPolicy.CREATIVE_FREE, 0L, List.of()).on(JobEvent.ADMITTED);
        return end == JobState.CANCELLED ? j.on(JobEvent.CANCEL) : j;
    }

    @Test
    void filesAreKeptUntilTheClaimIsReleased() {
        ClaimBook claims = new ClaimBook(8);
        claims.reserve("claim-a", new UUID(0, 1), "minecraft:overworld", new Box(0, 0, 0, 1, 1, 1), new Box(0, 0, 0, 1, 1, 1), 0L);
        claims.reserve("claim-b", new UUID(0, 1), "minecraft:overworld", new Box(9, 0, 0, 9, 1, 1), new Box(9, 0, 0, 9, 1, 1), 0L);
        claims.release("claim-b");
        ConstructionJob a = job("job-a", "claim-a", "h1", JobState.CANCELLED);
        ConstructionJob b = job("job-b", "claim-b", "h2", JobState.CANCELLED);
        assertEquals(Set.of("job-b"), OrphanSweep.forgettableJobs(List.of(a, b), claims), "a released claim's ended jobs go");
        List<String> files = List.of("jobs/job-a/journal.bin", "jobs/job-b/journal.bin", "manifests/h1.bin", "manifests/h2.bin",
                "claims/claim-a/placed.bin", "claims/claim-b/placed.bin", "claims.bin");
        assertEquals(List.of("claims/claim-b/placed.bin", "jobs/job-b/journal.bin", "manifests/h2.bin"),
                OrphanSweep.orphanFiles(files, List.of(a), claims));
    }
}
