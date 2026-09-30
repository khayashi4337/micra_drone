package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.khayashi4337.micradrone.build.compile.PlaceableBlockPolicy;
import io.github.khayashi4337.micradrone.build.compile.SiteSurvey;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The shipped sample is the P3 golden hut: the owner's first build is the one the golden file pins. */
class SampleHutTest {
    static String resource(String path) {
        try (InputStream in = SampleHutTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void theSampleIsTheGoldenPatchByteForByte() {
        assertEquals(resource("/build/golden/hut.patch.json"), resource("/data/micradrone/build_samples/hut.json"));
    }

    @Test
    void theSampleCompilesOnTheServerPathToTheGoldenHash() {
        String golden = resource("/build/golden/hut.manifest.txt").lines().findFirst().orElseThrow().substring("hash ".length());
        PlanFileReader.Result r = PlanFileReader.read(resource(PlanSource.SAMPLES.get("hut")), BuildingParts.registry());
        SiteSurvey air = SiteSurvey.air(TestManifests.DIM, TestManifests.hut().worldBounds());
        CompiledPlan c = PlanCompilation.compile(r.submission(), BuildingParts.registry(), PlaceableBlockPolicy.builtin(), air,
                Map.of());
        assertEquals(golden, c.manifest().hash());
        assertEquals(238, c.manifest().placements().size());
    }
}
