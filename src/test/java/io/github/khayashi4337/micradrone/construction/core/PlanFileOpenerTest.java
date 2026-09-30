package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanFileOpenerTest {
    private static final Path DIR = Path.of("/game/micradrone/plans").toAbsolutePath();

    /** A fake file system: the listed paths are links, and one path's real location is elsewhere. */
    private static PlanFileOpener.PathFacts fake(Set<Path> links, Path redirectFrom, Path redirectTo) {
        return new PlanFileOpener.PathFacts() {
            @Override
            public boolean isLinkLike(Path p) {
                return links.contains(p);
            }

            @Override
            public Path realPath(Path p) {
                return p.equals(redirectFrom) ? redirectTo : p;
            }
        };
    }

    @Test
    void aLinkOrJunctionAnywhereOnTheWayIsRefused() throws IOException {
        Path inner = DIR.resolve("my");
        Path file = inner.resolve("hut.json");
        assertEquals(Optional.empty(), PlanFileOpener.check(DIR, file, fake(Set.of(), null, null)));
        assertTrue(PlanFileOpener.check(DIR, file, fake(Set.of(inner), null, null)).orElseThrow().contains("junction"),
                "a folder in the plans folder that is a junction to C:\\\\ is caught");
        assertTrue(PlanFileOpener.check(DIR, file, fake(Set.of(file), null, null)).isPresent(), "the file itself a link");
        assertTrue(PlanFileOpener.check(DIR, file, fake(Set.of(), file, Path.of("/etc/passwd").toAbsolutePath()))
                .orElseThrow().contains("real file"), "a real path that leaves the folder");
        assertTrue(PlanFileOpener.check(DIR, DIR.resolve("../x.json"), fake(Set.of(), null, null)).isPresent());
    }

    @Test
    void aRealLinkOrJunctionIsRefusedWhenOneCanBeMadeHere(@TempDir Path root) throws Exception {
        Path plans = Files.createDirectories(root.resolve("micradrone/plans"));
        Path outside = Files.createDirectories(root.resolve("outside"));
        Files.writeString(outside.resolve("secret.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(plans.resolve("ok.json"), "{\"a\":1}", StandardCharsets.UTF_8);
        assertArrayEquals("{\"a\":1}".getBytes(StandardCharsets.UTF_8), PlanFileOpener.read(plans, plans.resolve("ok.json"), 100));
        Path link = plans.resolve("sneaky");
        boolean made;
        try {
            Files.createSymbolicLink(link, outside);
            made = true;
        } catch (IOException | UnsupportedOperationException noSymlink) {
            // Windows without the symlink privilege: a junction needs none
            made = System.getProperty("os.name").startsWith("Windows")
                    && new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), outside.toString()).start()
                    .waitFor(10, TimeUnit.SECONDS) && Files.exists(link);
        }
        Assumptions.assumeTrue(made, "neither a symbolic link nor a junction could be made here; the fake test covers it");
        assertTrue(Files.exists(link.resolve("secret.json")), "the link really reaches outside");
        assertThrows(IOException.class, () -> PlanFileOpener.read(plans, link.resolve("secret.json"), 100));
        assertThrows(IOException.class, () -> PlanFileOpener.read(plans, plans.resolve("ok.json"), 3), "size limit");
    }
}
