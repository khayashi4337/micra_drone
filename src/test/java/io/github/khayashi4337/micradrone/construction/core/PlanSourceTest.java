package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PlanSourceTest {
    private static final Path GAME = Path.of("C:/game");

    @Test
    void samplesAndFilesUnderThePlansFolderResolve() {
        assertEquals(new PlanSource.Sample("/data/micradrone/build_samples/hut.json"), PlanSource.resolve("sample:hut", GAME));
        PlanSource.FileAt f = assertInstanceOf(PlanSource.FileAt.class, PlanSource.resolve("my/hut2.json", GAME));
        assertEquals(GAME.resolve("micradrone/plans/my/hut2.json").normalize(), f.path());
    }

    @Test
    void anythingOutsideThePlansFolderIsRefused() {
        for (String bad : new String[]{"../secrets.json", "C:/x.json", "/etc/passwd", "a/../../b.json", "hut.txt", "",
                "sample:castle", "a\\b.json"}) {
            assertInstanceOf(PlanSource.Invalid.class, PlanSource.resolve(bad, GAME), bad);
        }
    }
}
