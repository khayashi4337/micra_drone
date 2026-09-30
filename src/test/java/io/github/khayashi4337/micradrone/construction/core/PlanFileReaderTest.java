package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.khayashi4337.micradrone.build.model.PlanJson;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.chat.MiniJson;
import org.junit.jupiter.api.Test;

class PlanFileReaderTest {
    @Test
    void aPatchAndTheWholePlanReadToTheSamePlan() {
        String patch = SampleHutTest.resource("/build/golden/hut.patch.json");
        PlanFileReader.Result a = PlanFileReader.read(patch, BuildingParts.registry());
        assertNull(a.error());
        String whole = MiniJson.write(PlanJson.toTree(TestManifests.hutPlan()));
        PlanFileReader.Result b = PlanFileReader.read(whole, BuildingParts.registry());
        assertNull(b.error());
        assertEquals(a.submission().plan().contentHash(), b.submission().plan().contentHash());
        assertEquals(JobKind.BUILD, a.submission().kind());
    }

    @Test
    void brokenJsonIsAnErrorNotACrash() {
        PlanFileReader.Result r = PlanFileReader.read("{\"ops\": [ {\"op\": \"nope\"} ]", BuildingParts.registry());
        assertNotNull(r.error());
        assertNull(r.submission());
    }
}
