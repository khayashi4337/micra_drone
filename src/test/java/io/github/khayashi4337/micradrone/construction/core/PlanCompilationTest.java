package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.TestParts;
import io.github.khayashi4337.micradrone.build.compile.PlaceableBlockPolicy;
import io.github.khayashi4337.micradrone.build.compile.SiteSurvey;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.plan.TemplateBundle;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanCompilationTest {
    private static final String GOLDEN_HASH = TestManifests.hut().hash();

    private static CompiledPlan compile(SemanticPlan plan, SiteSurvey survey) {
        return PlanCompilation.compile(new PlanSubmission(plan, TemplateBundle.EMPTY, JobKind.BUILD, null, null),
                BuildingParts.registry(), PlaceableBlockPolicy.builtin(), survey, Map.of());
    }

    @Test
    void theServerRebuildsTheGoldenHutByItself() {
        SemanticPlan hut = TestManifests.hutPlan();
        CompiledPlan c = compile(hut, SiteSurvey.air(hut.site().dimension(), TestManifests.hut().worldBounds()));
        assertEquals(GOLDEN_HASH, c.manifest().hash());
        assertEquals("micra:wall", c.nodeTypes().get("wall-n"));
        assertEquals(c.manifest().worldBounds(), c.operatingBox());
        assertEquals(0, c.terrain().cut() + c.terrain().fill());
    }

    @Test
    void thePinnedSurveyAloneDecidesTheTerrainPartOfTheHash() {
        SemanticPlan hut = TestManifests.hutPlan();
        var bounds = TestManifests.hut().worldBounds();
        SiteSurvey ground = SiteSurvey.flat(hut.site().dimension(), bounds, 63, "minecraft:grass_block");
        String a = compile(hut, ground).manifest().hash();
        assertEquals(a, compile(hut, ground).manifest().hash(), "same pinned survey, same hash");
        assertNotEquals(GOLDEN_HASH, a, "sinking the foundation into the ground changes the replace policies");
    }

    @Test
    void aPlanWithoutASiteIsAnIssueNotACrash() {
        CompiledPlan c = compile(SemanticPlan.empty("x"), SiteSurvey.air(TestManifests.DIM, TestManifests.hut().worldBounds()));
        assertNull(c.manifest());
        assertTrue(c.issues().stream().anyMatch(i -> i.code() == IssueCode.E_SITE_MISSING));
    }

    @Test
    void aBundledTemplateMustMatchTheServersOwnCopyByIdAndHash() {
        SemanticPlan hut = TestManifests.hutPlan();
        SiteSurvey air = SiteSurvey.air(hut.site().dimension(), TestManifests.hut().worldBounds());
        TemplateBundle sent = TestParts.bundle();
        String id = TestParts.lineTemplate().id();
        for (Map<String, String> known : List.of(Map.<String, String>of(), Map.of(id, "0000-tampered"))) {
            CompiledPlan c = PlanCompilation.compile(new PlanSubmission(hut, sent, JobKind.BUILD, null, null),
                    BuildingParts.registry(), PlaceableBlockPolicy.builtin(), air, known);
            assertNull(c.manifest(), "an unknown or altered template is refused before anything is compiled");
            assertNull(c.operatingBox());
            assertTrue(c.issues().stream().anyMatch(i -> i.code() == IssueCode.E_TEMPLATE_UNVERIFIED));
        }
        CompiledPlan ok = PlanCompilation.compile(new PlanSubmission(hut, sent, JobKind.BUILD, null, null),
                BuildingParts.registry(), PlaceableBlockPolicy.builtin(), air, Map.of(id, TestParts.lineTemplate().hash()));
        assertTrue(ok.issues().stream().noneMatch(i -> i.code() == IssueCode.E_TEMPLATE_UNVERIFIED));
    }

    @Test
    void aSurveyOfAnotherDimensionIsABug() {
        SemanticPlan hut = TestManifests.hutPlan();
        assertThrows(IllegalArgumentException.class,
                () -> compile(hut, SiteSurvey.air("minecraft:the_nether", TestManifests.hut().worldBounds())));
    }
}
