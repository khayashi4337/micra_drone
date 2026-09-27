package io.github.khayashi4337.micradrone.build.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.TestParts;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * {@link PlanExpander} against {@link ReferencePlanExpander} (the expander as it was before its template work moved from
 * every instance to every distinct template) on random plans and random templates, most of them wrong in some way. For
 * every seed the two must give the same result: same issues in the same order, same nodes, connections and hashes.
 */
class PlanExpanderDifferentialTest {
    private static final int SEEDS = 1_500;
    private static final long SEED_BASE = 20_260_927_000L;
    private static final int POOL_SIZE = 4;
    /** The chance of each kind of mistake a template holds, per part and per connection: chosen per plan, 0 for half of them. */
    private static final int[] ERROR_PERCENTS = {0, 0, 10, 30};
    /** The chance of a mistake in the plan itself, chosen per plan. */
    private static final int[] PLAN_ERROR_PERCENTS = {0, 0, 10, 30};
    private static final int DUPLICATE_TEMPLATE_PERCENT = 10;
    private static final int UNRESOLVED_SLOTS_PERCENT = 40;
    private static final String TEMPLATE_PREFIX = "mod:r";

    /** What one expansion gave: the result, or the exception it threw (both sides must throw alike). */
    private record Outcome(ExpandResult result, String thrown) {
    }

    private static Outcome outcomeOf(Supplier<ExpandResult> expansion) {
        try {
            return new Outcome(expansion.get(), null);
        } catch (RuntimeException e) {
            return new Outcome(null, e.getClass().getName() + ": " + e.getMessage());
        }
    }

    @Test
    void theExpanderGivesTheResultsOfTheReferenceOnRandomPlans() {
        int succeeded = 0;
        int withInstancesExpanded = 0;
        int thrown = 0;
        Map<IssueCode, Integer> plansWithCode = new EnumMap<>(IssueCode.class);
        for (int seed = 0; seed < SEEDS; seed++) {
            Random r = ExpanderFixtures.random(SEED_BASE + seed);
            int templateErrorPercent = ERROR_PERCENTS[r.nextInt(ERROR_PERCENTS.length)];
            List<ModuleTemplate> pool = new ArrayList<>();
            for (int i = 0; i < POOL_SIZE; i++) {
                pool.add(ExpanderFixtures.template(r, TEMPLATE_PREFIX + i, templateErrorPercent));
            }
            if (ExpanderFixtures.chance(r, DUPLICATE_TEMPLATE_PERCENT)) {
                // the same id twice: the first one is the template
                pool.add(ExpanderFixtures.template(r, TEMPLATE_PREFIX + 0, templateErrorPercent));
            }
            TemplateBundle bundle = new TemplateBundle(pool);
            List<String> templateIds = pool.stream().map(ModuleTemplate::id).distinct().toList();
            SemanticPlan plan = ExpanderFixtures.randomPlan(r, templateIds,
                    PLAN_ERROR_PERCENTS[r.nextInt(PLAN_ERROR_PERCENTS.length)]);
            SlotResolver slots = ExpanderFixtures.chance(r, UNRESOLVED_SLOTS_PERCENT) ? SlotResolver.NONE : ExpanderFixtures.SLOTS;
            Router router = r.nextBoolean() ? Router.NONE : ExpanderFixtures.ROUTER;

            Outcome expected = outcomeOf(() -> new ReferencePlanExpander(TestParts.registry(), slots).expand(plan, bundle, router));
            Outcome actual = outcomeOf(() -> new PlanExpander(TestParts.registry(), slots).expand(plan, bundle, router));
            assertEquals(expected, actual, "seed " + seed);

            if (expected.thrown() != null) {
                thrown++;
                continue;
            }
            ExpandResult result = expected.result();
            if (result.plan() != null) {
                succeeded++;
                if (!result.plan().templateHashes().isEmpty()) {
                    withInstancesExpanded++;
                }
            }
            for (IssueCode code : result.issues().stream().map(Issue::code).distinct().toList()) {
                plansWithCode.merge(code, 1, Integer::sum);
            }
        }
        System.out.println("[differential] " + SEEDS + " plans: " + succeeded + " expanded, " + withInstancesExpanded
                + " of them with a module instance, " + thrown + " threw; plans with each issue code: " + plansWithCode);
        // the comparison is only worth something if both outcomes and every kind of issue were compared many times
        assertTrue(succeeded >= SEEDS / 5, "expanded plans: " + succeeded);
        assertTrue(withInstancesExpanded >= SEEDS / 10, "expanded plans with an instance: " + withInstancesExpanded);
        for (IssueCode code : List.of(IssueCode.E_ID_INVALID, IssueCode.E_ID_DUPLICATE, IssueCode.E_PARAM_RANGE,
                IssueCode.E_UNKNOWN_PART, IssueCode.E_ANCHOR, IssueCode.E_CONN_INVALID, IssueCode.E_NO_ROUTE)) {
            assertTrue(plansWithCode.getOrDefault(code, 0) >= SEEDS / 20, code + " in " + plansWithCode.get(code) + " plans");
        }
    }
}
