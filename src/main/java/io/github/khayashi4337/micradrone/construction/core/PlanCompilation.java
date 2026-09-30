package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.CompileResult;
import io.github.khayashi4337.micradrone.build.compile.PlaceableBlockPolicy;
import io.github.khayashi4337.micradrone.build.compile.PlanCompiler;
import io.github.khayashi4337.micradrone.build.compile.SiteSurvey;
import io.github.khayashi4337.micradrone.build.compile.TerrainResult;
import io.github.khayashi4337.micradrone.build.compile.TerrainPrep;
import io.github.khayashi4337.micradrone.build.compile.TerrainSummary;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import io.github.khayashi4337.micradrone.build.plan.ExpandResult;
import io.github.khayashi4337.micradrone.build.plan.PatchResult;
import io.github.khayashi4337.micradrone.build.plan.PlanExpander;
import io.github.khayashi4337.micradrone.build.plan.PlanPatcher;
import io.github.khayashi4337.micradrone.build.plan.Router;
import io.github.khayashi4337.micradrone.build.plan.SlotResolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The server's own rebuild of a submitted plan (04 F-3, D-3): templates checked against the server's copies,
 * parameters re-typed and re-checked, expanded, compiled and terraformed from the pinned survey, all with the server's
 * code. Nothing the client computed is an input. Pure and deterministic, so it runs on the worker pool.
 */
public final class PlanCompilation {
    private static final TerrainSummary NO_TERRAIN = new TerrainSummary(0, 0);
    /** Inside the compile the SurveyRef is only recorded (it is not hashed); the pin's expiry lives in SurveyCache. */
    private static final long SURVEY_NOT_CACHED_TICK = 0L;

    private PlanCompilation() {
    }

    public static CompiledPlan compile(PlanSubmission s, PartTypeRegistry registry, PlaceableBlockPolicy policy, SiteSurvey survey,
                                       Map<String, String> bundledTemplateHashes) {
        List<Issue> issues = new ArrayList<>(s.templates().verifyAgainst(bundledTemplateHashes));
        if (hasError(issues)) {
            return failed(issues);
        }
        PatchResult normalized = new PlanPatcher(registry, s.templates()).normalize(s.plan());
        issues.addAll(normalized.issues());
        if (!normalized.ok()) {
            return failed(issues);
        }
        SemanticPlan plan = normalized.plan();
        if (plan.site() != null && !plan.site().dimension().equals(survey.dimension())) {
            throw new IllegalArgumentException("the survey must be taken in the plan's own dimension");
        }
        ExpandResult expanded = new PlanExpander(registry, SlotResolver.NONE).expand(plan, s.templates(), Router.NONE);
        issues.addAll(expanded.issues());
        if (expanded.plan() == null || hasError(expanded.issues())) {
            return failed(issues);
        }
        CompileResult compiled = new PlanCompiler().compile(expanded.plan(), registry, policy, survey.ref(SURVEY_NOT_CACHED_TICK));
        issues.addAll(compiled.issues());
        if (compiled.manifest() == null) {
            return failed(issues);
        }
        TerrainResult terrain = TerrainPrep.apply(compiled.manifest(), survey);
        issues.addAll(terrain.issues());
        if (terrain.manifest() == null) {
            return failed(issues);
        }
        Map<String, String> nodeTypes = new HashMap<>();
        for (PlanNode n : expanded.plan().primitiveNodes()) {
            nodeTypes.put(n.id(), n.type());
        }
        return new CompiledPlan(terrain.manifest(), issues, terrain.summary(), nodeTypes,
                OperatingBox.of(terrain.manifest(), plan));
    }

    private static boolean hasError(List<Issue> issues) {
        return issues.stream().anyMatch(Issue::isError);
    }

    private static CompiledPlan failed(List<Issue> issues) {
        return new CompiledPlan(null, issues, NO_TERRAIN, Map.of(), null);
    }
}
