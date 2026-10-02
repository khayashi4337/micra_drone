package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NioFileSystemTest {
    @TempDir
    Path root;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void writesReplaceAtomicallyAndReadsBack() throws IOException {
        NioFileSystem fs = new NioFileSystem(root);
        fs.writeAtomic("jobs/job-1/journal.bin", b("one"));
        fs.writeAtomic("jobs/job-1/journal.bin", b("two"));
        assertArrayEquals(b("two"), fs.read("jobs/job-1/journal.bin").orElseThrow());
        assertTrue(fs.read("jobs/job-1/nothing.bin").isEmpty());
        assertEquals(List.of("jobs/job-1/journal.bin"), fs.list("jobs"));
        fs.delete("jobs/job-1/journal.bin");
        assertTrue(fs.read("jobs/job-1/journal.bin").isEmpty());
    }

    @Test
    void aCrashBetweenWriteAndMoveLeavesTheOldFileAndTheTempIsCleanedLater() throws IOException {
        NioFileSystem ok = new NioFileSystem(root);
        ok.writeAtomic("claims.bin", b("old"));
        NioFileSystem crashing = new NioFileSystem(root, () -> {
            throw new IOException("power cut before the rename");
        });
        assertThrows(IOException.class, () -> crashing.writeAtomic("claims.bin", b("new")));
        assertArrayEquals(b("old"), ok.read("claims.bin").orElseThrow(), "the old file is intact");
        try (var files = Files.list(root)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().contains(NioFileSystem.TEMP_MARK)));
        }
        ok.cleanTemp();
        try (var files = Files.list(root)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().contains(NioFileSystem.TEMP_MARK)));
        }
    }

    @Test
    void directoriesAreListedWithTheTrailingSlashTheRuntimeUses() throws IOException {
        // the runtime lists ConstructionJob.JOBS_DIR ("jobs/") and JobFiles.CLAIMS_DIR ("claims/"); a world with the mod
        // crashed at server start when this threw "not a safe relative path: claims/" (found in the first real restart run)
        NioFileSystem fs = new NioFileSystem(root);
        fs.writeAtomic(ConstructionJob.JOBS_DIR + "job-1/journal.bin", b("one"));
        fs.writeAtomic(JobFiles.CLAIMS_DIR + "claim-1.bin", b("two"));
        assertEquals(List.of("jobs/job-1/journal.bin"), fs.list(ConstructionJob.JOBS_DIR));
        assertEquals(List.of("claims/claim-1.bin"), fs.list(JobFiles.CLAIMS_DIR));
        assertEquals(List.of(), fs.list("manifests/"), "a directory that does not exist lists as empty");
        for (String bad : new String[]{"../", "/etc/", "a/../../x/"}) {
            assertThrows(IllegalArgumentException.class, () -> fs.list(bad), bad);
        }
    }

    @Test
    void pathsCannotLeaveTheRoot() {
        NioFileSystem fs = new NioFileSystem(root);
        for (String bad : new String[]{"../x.bin", "/etc/passwd", "C:/x", "a\\b", "a/../../x"}) {
            assertThrows(IllegalArgumentException.class, () -> fs.writeAtomic(bad, b("x")), bad);
        }
    }

    @Test
    void theTempFileIsForcedToTheDiskBeforeItIsRenamed() throws IOException {
        List<Path> synced = new ArrayList<>();
        List<Boolean> targetExistedAtSync = new ArrayList<>();
        Path target = root.resolve("claims.bin");
        NioFileSystem fs = new NioFileSystem(root, () -> {
        }, true, p -> {
            synced.add(p);
            targetExistedAtSync.add(Files.exists(target));
        });
        fs.writeAtomic("claims.bin", b("one"));
        assertTrue(synced.get(0).getFileName().toString().contains(NioFileSystem.TEMP_MARK), "the temp file is forced");
        assertEquals(false, targetExistedAtSync.get(0), "before the rename puts it in place");
    }

    @Test
    void withoutAnAtomicRenameTheLastCommittedGenerationIsRead() throws IOException {
        NioFileSystem ok = new NioFileSystem(root, () -> {
        }, false, p -> {
        });
        ok.writeAtomic("jobs/claims.bin", SealedFile.seal(b("old")));
        NioFileSystem crashing = new NioFileSystem(root, () -> {
            throw new IOException("power cut between the two renames");
        }, false, p -> {
        });
        assertThrows(IOException.class, () -> crashing.writeAtomic("jobs/claims.bin", SealedFile.seal(b("new"))));
        assertArrayEquals(b("old"), SealedFile.unseal(ok.read("jobs/claims.bin").orElseThrow()).orElseThrow(),
                "the file is missing, so the previous generation is read");
        assertEquals(List.of("jobs/claims.bin"), ok.list("jobs"));
        // a torn copy of a new generation is not committed (its seal fails): the previous generation is read, and kept
        Files.write(root.resolve("jobs/claims.bin"), Arrays.copyOf(SealedFile.seal(b("torn")), 12));
        assertArrayEquals(b("old"), SealedFile.unseal(ok.read("jobs/claims.bin").orElseThrow()).orElseThrow());
        ok.writeAtomic("jobs/claims.bin", SealedFile.seal(b("next")));
        assertArrayEquals(b("next"), SealedFile.unseal(ok.read("jobs/claims.bin").orElseThrow()).orElseThrow());
        assertTrue(Files.notExists(root.resolve("jobs/claims.bin" + NioFileSystem.PREVIOUS_SUFFIX)), "gone once in place");
    }
}
