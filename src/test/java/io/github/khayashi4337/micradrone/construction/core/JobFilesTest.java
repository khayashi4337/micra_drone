package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JobFilesTest {
    /** A service whose job-1 has run one tick (its claim reserved, some blocks placed). */
    static JobService halfway(FakeJobWorld w) {
        JobService s = JobServiceTest.service();
        w.online.add(JobServiceTest.A);
        PlacementManifest m = TestManifests.smallHut();
        s.admitApproved(JobServiceTest.approved("job-1", JobServiceTest.A, m, MaterialPolicy.CREATIVE_FREE), m,
                Map.of("wall", "micra:wall"), JobProgram.build(m), m.worldBounds());
        s.tick(new TickInput(0, JobServiceTest.CALM), w);
        return s;
    }

    static JobRecord builtHalfway(FakeJobWorld w) {
        return halfway(w).record("job-1").orElseThrow();
    }

    @Test
    void aJobSavesToItsFilesAndLoadsBackTheSame() throws Exception {
        InMemoryFileSystem fs = new InMemoryFileSystem();
        JobFiles files = new JobFiles(fs);
        JobService s = halfway(new FakeJobWorld());
        JobRecord r = s.record("job-1").orElseThrow();
        files.saveJob(r, new LedgerBook());
        assertTrue(fs.files.containsKey("manifests/" + r.manifest().hash() + ".bin"));
        assertTrue(fs.files.containsKey("jobs/job-1/journal.bin"));
        assertFalse(fs.files.containsKey("jobs/job-1/program.bin"), "a BUILD's program is the manifest's, rebuilt at load");
        assertTrue(fs.files.containsKey("jobs/job-1/job.bin"), "the job itself: no SavedData list any more");
        assertFalse(fs.files.containsKey("jobs/job-1/pending_log.bin"), "no answer is awaited");
        JobLoad.Loaded back = assertInstanceOf(JobLoad.Loaded.class, files.loadJob(r.job(), s.claims()));
        assertEquals(r.journal().records(), back.record().journal().records());
        assertEquals(r.manifest(), back.record().manifest());
        assertEquals(Map.of("wall", "micra:wall"), back.record().nodeTypes());
        assertEquals(r.operatingBox(), back.record().operatingBox(), "the operating box comes from the claim");
    }

    @Test
    void aJobWaitingForItsOwnersAnswerKeepsItsPartOfTheLog() throws Exception {
        InMemoryFileSystem fs = new InMemoryFileSystem();
        JobFiles files = new JobFiles(fs);
        JobService s = halfway(new FakeJobWorld());
        JobRecord r = s.record("job-1").orElseThrow();
        List<WalEntry> part = List.of(new WalEntry.RunStart("job-1", 7), new WalEntry.PlaceIntent("job-1", 7,
                new JournalRecord(3, r.manifest().placements().get(3).pos(), BlockSpec.AIR, false,
                        r.manifest().placements().get(3).block(), 3)));
        r.pendingLog = part;
        files.saveJob(r, new LedgerBook());
        assertTrue(fs.files.containsKey("jobs/job-1/pending_log.bin"));
        JobLoad.Loaded back = assertInstanceOf(JobLoad.Loaded.class, files.loadJob(r.job(), s.claims()));
        assertEquals(part, back.record().pendingLog, "the answer is still possible after a restart");
        r.pendingLog = List.of();
        files.saveJob(r, new LedgerBook());
        assertFalse(fs.files.containsKey("jobs/job-1/pending_log.bin"));
    }

    @Test
    void aMissingOrDamagedFileOrAMissingClaimIsBrokenWithAReason() throws Exception {
        InMemoryFileSystem fs = new InMemoryFileSystem();
        JobFiles files = new JobFiles(fs);
        JobService s = halfway(new FakeJobWorld());
        JobRecord r = s.record("job-1").orElseThrow();
        files.saveJob(r, new LedgerBook());
        fs.files.remove("jobs/job-1/journal.bin");
        JobLoad.Broken missing = assertInstanceOf(JobLoad.Broken.class, files.loadJob(r.job(), s.claims()));
        assertTrue(missing.reasons().get(0).contains("journal"), missing.reasons().toString());
        assertEquals(r.manifest(), missing.manifestOrNull(), "the manifest is still usable for a repair");
        files.saveJob(r, new LedgerBook());
        byte[] ledger = fs.files.get("jobs/job-1/ledger.bin");
        ledger[ledger.length - 1] ^= 1;
        assertInstanceOf(JobLoad.Broken.class, files.loadJob(r.job(), s.claims()));
        files.saveJob(r, new LedgerBook());
        JobLoad.Broken noClaim = assertInstanceOf(JobLoad.Broken.class,
                files.loadJob(r.job(), new ClaimBook(ClaimBook.DEFAULT_MAX_CLAIMS_PER_OWNER)));
        assertTrue(noClaim.reasons().contains("claim: missing"), noClaim.reasons().toString());
    }
}
