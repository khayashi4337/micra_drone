package io.github.khayashi4337.micradrone.build.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.TestParts;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The instances of a plan do not depend on each other: expanding a plan that holds k module instances gives what the k
 * plans of one instance each give, put one after the other. This is what lets the expander do the work of a template once
 * and then only prefix and place: nothing it remembers from one instance may change what another one gets.
 */
class PlanExpanderInstancesPropertyTest {
    private static final int SEEDS = 600;
    private static final long SEED_BASE = 20_260_928_000L;
    private static final int POOL_SIZE = 3;
    private static final int MIN_INSTANCES = 2;
    private static final int MAX_EXTRA_INSTANCES = 7;
    /** Chances, in percent, of a mistake in a template (per part and connection) and in an instance's anchor: chosen per plan. */
    private static final int[] TEMPLATE_ERROR_PERCENTS = {0, 0, 10, 30};
    private static final int[] INSTANCE_ERROR_PERCENTS = {0, 0, 0, 5, 20};
    private static final int MISSING_TEMPLATE_PERCENT = 5;
    private static final String TEMPLATE_PREFIX = "mod:q";
    private static final String MISSING_TEMPLATE = "mod:not-in-the-bundle";
    private static final String INSTANCE_PREFIX = "i";

    private static final Comparator<Issue> BY_ID_THEN_MESSAGE = Comparator.comparing(Issue::id).thenComparing(Issue::message);

    @Test
    void kInstancesExpandLikeTheKSingleInstanceExpansionsOneAfterTheOther() {
        int allClean = 0;
        int withIssues = 0;
        int instancesExpanded = 0;
        for (int seed = 0; seed < SEEDS; seed++) {
            Random r = ExpanderFixtures.random(SEED_BASE + seed);
            List<ModuleTemplate> pool = new ArrayList<>();
            pool.add(TestParts.lineTemplate());
            int templateErrorPercent = TEMPLATE_ERROR_PERCENTS[r.nextInt(TEMPLATE_ERROR_PERCENTS.length)];
            for (int i = 0; i < POOL_SIZE; i++) {
                pool.add(ExpanderFixtures.template(r, TEMPLATE_PREFIX + i, templateErrorPercent));
            }
            TemplateBundle bundle = new TemplateBundle(pool);
            int k = MIN_INSTANCES + r.nextInt(MAX_EXTRA_INSTANCES);
            int instanceErrorPercent = INSTANCE_ERROR_PERCENTS[r.nextInt(INSTANCE_ERROR_PERCENTS.length)];
            List<PlanNode> instances = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                String type = ExpanderFixtures.chance(r, MISSING_TEMPLATE_PERCENT) ? MISSING_TEMPLATE
                        : pool.get(r.nextInt(pool.size())).id();
                instances.add(ExpanderFixtures.instance(INSTANCE_PREFIX + i, type, null,
                        ExpanderFixtures.instanceAnchor(r, instanceErrorPercent)));
            }
            PlanExpander expander = new PlanExpander(TestParts.registry(), ExpanderFixtures.SLOTS);

            ExpandResult whole = expander.expand(ExpanderFixtures.plan(instances, List.of()), bundle, ExpanderFixtures.ROUTER);
            List<ExpandResult> singles = new ArrayList<>();
            for (PlanNode instance : instances) {
                singles.add(expander.expand(ExpanderFixtures.plan(List.of(instance), List.of()), bundle, ExpanderFixtures.ROUTER));
            }

            // the issues are the same set: the order of the phases (the instances, then the origins) may interleave differently
            List<Issue> expectedIssues = singles.stream().flatMap(s -> s.issues().stream()).sorted(BY_ID_THEN_MESSAGE).toList();
            assertEquals(expectedIssues, whole.issues().stream().sorted(BY_ID_THEN_MESSAGE).toList(), "seed " + seed);
            boolean anyError = singles.stream().anyMatch(s -> s.plan() == null);
            if (anyError) {
                withIssues++;
                assertNull(whole.plan(), "seed " + seed);
                continue;
            }
            allClean++;
            assertNotNull(whole.plan(), "seed " + seed);
            List<PlanNode> expectedNodes = singles.stream().flatMap(s -> s.plan().primitiveNodes().stream()).toList();
            List<RoutedConnection> expectedRouted = singles.stream().flatMap(s -> s.plan().routed().stream()).toList();
            assertEquals(expectedNodes, whole.plan().primitiveNodes(), "seed " + seed);
            assertEquals(expectedRouted, whole.plan().routed(), "seed " + seed);
            List<String> expectedHashes = singles.stream().flatMap(s -> s.plan().templateHashes().stream()).distinct().sorted().toList();
            assertEquals(expectedHashes, whole.plan().templateHashes(), "seed " + seed);
            instancesExpanded += k;
        }
        System.out.println("[property] " + SEEDS + " plans: " + allClean + " with every instance expanded (" + instancesExpanded
                + " instances), " + withIssues + " with issues");
        assertTrue(allClean >= SEEDS / 5, "plans where every instance expanded: " + allClean);
        assertTrue(withIssues >= SEEDS / 5, "plans with issues: " + withIssues);
    }

    @Test
    void theOrderOfTheInstancesOnlyDecidesTheOrderOfTheirParts() {
        ModuleTemplate line = TestParts.lineTemplate();
        TemplateBundle bundle = TestParts.bundle();
        PlanNode a = ExpanderFixtures.instance("a", line.id(), null, ExpanderFixtures.instanceAnchor(ExpanderFixtures.random(1), 0));
        PlanNode b = ExpanderFixtures.instance("b", line.id(), null, ExpanderFixtures.instanceAnchor(ExpanderFixtures.random(2), 0));
        PlanExpander expander = new PlanExpander(TestParts.registry(), ExpanderFixtures.SLOTS);
        ExpandedPlan ab = expander.expand(ExpanderFixtures.plan(List.of(a, b), List.<Connection>of()), bundle, Router.NONE).plan();
        ExpandedPlan ba = expander.expand(ExpanderFixtures.plan(List.of(b, a), List.<Connection>of()), bundle, Router.NONE).plan();
        List<PlanNode> partsOfA = ab.primitiveNodes().subList(0, line.nodes().size());
        List<PlanNode> partsOfB = ab.primitiveNodes().subList(line.nodes().size(), 2 * line.nodes().size());
        assertEquals(partsOfB, ba.primitiveNodes().subList(0, line.nodes().size()));
        assertEquals(partsOfA, ba.primitiveNodes().subList(line.nodes().size(), 2 * line.nodes().size()));
        assertEquals(ab.templateHashes(), ba.templateHashes());
    }
}
