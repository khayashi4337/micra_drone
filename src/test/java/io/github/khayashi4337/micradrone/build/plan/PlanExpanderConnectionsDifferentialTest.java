package io.github.khayashi4337.micradrone.build.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.TestParts;
import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.ConnKind;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Constraints;
import io.github.khayashi4337.micradrone.build.model.Dir6;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.PortRef;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.Routing;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.parts.PartCategory;
import io.github.khayashi4337.micradrone.build.parts.PortKind;
import io.github.khayashi4337.micradrone.build.parts.PortSpec;
import io.github.khayashi4337.micradrone.build.parts.VersionRange;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The expansion of connections is the same as it was before the connections were counted: {@link PlanExpander} against
 * {@link ReferencePlanExpander} on plans that are under the limit, whose templates hold many internal connections (0 to
 * 30, some with bad ids, unknown ends or ports, via parts that are not there) and whose plans hold connections of their
 * own to instance ports and to parts inside instances. Results are compared whole: issues in order, nodes, routed
 * connections (ids, paths), hashes. Seeds go through {@link ExpanderFixtures#random}, which mixes them.
 */
class PlanExpanderConnectionsDifferentialTest {
    private static final int SEEDS = 800;
    private static final long SEED_BASE = 20_260_929_000L;
    private static final int MAX_CONNECTIONS = 30;
    private static final int MAX_PARTS = 3;
    private static final int MAX_INSTANCES = 6;
    private static final int MAX_OWN_CONNECTIONS = 6;
    /** The chance of each kind of mistake per connection: chosen per plan, 0 for half of them. */
    private static final int[] MISTAKE_PERCENTS = {0, 0, 10, 30};
    private static final int PERCENT = 100;
    private static final String TEMPLATE_PREFIX = "mod:w";
    private static final String MISSING_TEMPLATE = "mod:not-in-the-bundle";
    private static final int MISSING_TEMPLATE_PERCENT = 3;
    private static final String GHOST = "ghost";
    private static final String OUT = "out";
    private static final String IN = "in";
    private static final int MID_INSTANCES = 3_000;
    private static final int MID_CONNECTIONS = 10;

    private static boolean chance(Random r, int percent) {
        return ExpanderFixtures.chance(r, percent);
    }

    private static <T> T pick(Random r, List<T> items) {
        return items.get(r.nextInt(items.size()));
    }

    private static Routing routing(Random r, List<String> viaCandidates, int mistakePercent) {
        if (r.nextBoolean()) {
            return Routing.AUTO;
        }
        List<String> via = new ArrayList<>();
        int count = r.nextInt(3);
        for (int i = 0; i < count; i++) {
            via.add(chance(r, mistakePercent) ? GHOST : pick(r, viaCandidates));
        }
        return new Routing.Explicit(via);
    }

    /** A motor {@code m} and shafts {@code s0..}, all below the motor, with many internal connections between them. */
    private static ModuleTemplate connectedTemplate(Random r, String id, int mistakePercent) {
        int shafts = 1 + r.nextInt(MAX_PARTS);
        List<PlanNode> parts = new ArrayList<>();
        List<String> shaftIds = new ArrayList<>();
        parts.add(TestParts.at("m", ExpanderFixtures.MOTOR, null, 0, 0, 0));
        for (int i = 0; i < shafts; i++) {
            shaftIds.add("s" + i);
            parts.add(TestParts.at("s" + i, ExpanderFixtures.SHAFT, "m", i + 1, 0, 0));
        }
        List<String> partIds = new ArrayList<>(shaftIds);
        partIds.add("m");
        List<Connection> internal = new ArrayList<>();
        int count = r.nextInt(MAX_CONNECTIONS + 1);
        for (int j = 0; j < count; j++) {
            String connectionId = chance(r, mistakePercent) ? pick(r, List.of("RUN", "c0")) : "c" + j;
            String from = chance(r, mistakePercent) ? GHOST : "m";
            String to = pick(r, shaftIds);
            String toPort = chance(r, mistakePercent) ? "nope" : IN;
            internal.add(new Connection(connectionId, new PortRef(from, OUT), new PortRef(to, toPort), ConnKind.ROTATION,
                    routing(r, partIds, mistakePercent), r.nextBoolean() ? Constraints.NONE
                            : new Constraints(null, Set.of(pick(r, partIds)), null, Set.of())));
        }
        List<PortSpec> ports = List.of(new PortSpec(OUT, PortKind.ROTATION_OUT, new LocalPos(1, 0, 0), Dir6.EAST, Set.of()));
        return new ModuleTemplate(1, id, "k." + id, PartCategory.MODULE, VersionRange.ALWAYS, null, ports, parts, internal, null, null,
                Set.of());
    }

    @Test
    void connectionsExpandAsBeforeOnPlansUnderTheLimit() {
        int expanded = 0;
        long routedCompared = 0;
        int plansWithManyConnections = 0;
        Map<IssueCode, Integer> plansWithCode = new EnumMap<>(IssueCode.class);
        for (int seed = 0; seed < SEEDS; seed++) {
            Random r = ExpanderFixtures.random(SEED_BASE + seed);
            int mistakePercent = MISTAKE_PERCENTS[r.nextInt(MISTAKE_PERCENTS.length)];
            List<ModuleTemplate> pool = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                pool.add(connectedTemplate(r, TEMPLATE_PREFIX + i, mistakePercent));
            }
            TemplateBundle bundle = new TemplateBundle(pool);

            List<PlanNode> nodes = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            List<String> partsInsideInstances = new ArrayList<>();
            int instanceCount = 1 + r.nextInt(MAX_INSTANCES);
            for (int i = 0; i < instanceCount; i++) {
                String id = "i" + i;
                ids.add(id);
                boolean missing = chance(r, MISSING_TEMPLATE_PERCENT * (mistakePercent == 0 ? 0 : 1));
                String type = missing ? MISSING_TEMPLATE : pick(r, pool).id();
                nodes.add(ExpanderFixtures.instance(id, type, null,
                        new Anchor.Absolute(new LocalPos(10 * i, 0, 0), chance(r, PERCENT / 2) ? Rot.NONE : new Rot(r.nextInt(4), false))));
                partsInsideInstances.add(id + "/s0");
            }
            nodes.add(TestParts.at("plain-shaft", ExpanderFixtures.SHAFT, null, 100, 0, 0));
            ids.add("plain-shaft");
            List<Connection> own = new ArrayList<>();
            int ownCount = r.nextInt(MAX_OWN_CONNECTIONS + 1);
            for (int j = 0; j < ownCount; j++) {
                String from = chance(r, mistakePercent) ? GHOST : pick(r, ids);
                own.add(new Connection("k" + j, new PortRef(from, OUT), new PortRef("plain-shaft", IN), ConnKind.ROTATION,
                        routing(r, partsInsideInstances, mistakePercent), Constraints.NONE));
            }
            SemanticPlan plan = ExpanderFixtures.plan(nodes, own);
            // a plan with nothing wrong on purpose gets a router, so that its automatic connections can be routed
            Router router = mistakePercent == 0 || r.nextBoolean() ? ExpanderFixtures.ROUTER : Router.NONE;

            ExpandResult expected = new ReferencePlanExpander(TestParts.registry(), SlotResolver.NONE).expand(plan, bundle, router);
            ExpandResult actual = new PlanExpander(TestParts.registry(), SlotResolver.NONE).expand(plan, bundle, router);
            assertEquals(expected, actual, "seed " + seed);

            if (expected.plan() != null) {
                expanded++;
                routedCompared += expected.plan().routed().size();
                if (expected.plan().routed().size() >= MAX_CONNECTIONS) {
                    plansWithManyConnections++;
                }
            }
            for (IssueCode code : expected.issues().stream().map(Issue::code).distinct().toList()) {
                plansWithCode.merge(code, 1, Integer::sum);
            }
        }
        System.out.println("[connections-differential] " + SEEDS + " plans: " + expanded + " expanded, " + routedCompared
                + " routed connections compared, " + plansWithManyConnections + " plans with at least " + MAX_CONNECTIONS
                + " routed; plans with each issue code: " + plansWithCode);
        assertTrue(expanded >= SEEDS / 3, "expanded plans: " + expanded);
        assertTrue(routedCompared >= 5_000, "routed connections compared: " + routedCompared);
        assertTrue(plansWithManyConnections >= SEEDS / 20, "plans with many routed connections: " + plansWithManyConnections);
        for (IssueCode code : List.of(IssueCode.E_CONN_INVALID, IssueCode.E_NO_ROUTE, IssueCode.E_ID_INVALID, IssueCode.E_ID_DUPLICATE)) {
            assertTrue(plansWithCode.getOrDefault(code, 0) >= SEEDS / 20, code + " in " + plansWithCode.get(code) + " plans");
        }
    }

    @Test
    void aBigPlanUnderTheLimitExpandsAsBefore() {
        // 3,000 instances x 10 connections = 30,000 connections (and 3,000 parts): the whole result equals the reference's
        Random r = ExpanderFixtures.random(SEED_BASE);
        ModuleTemplate template = connectedTemplate(r, TEMPLATE_PREFIX + 0, 0);
        List<Connection> internal = new ArrayList<>();
        for (int j = 0; j < MID_CONNECTIONS; j++) {
            internal.add(new Connection("c" + j, new PortRef("m", OUT), new PortRef("s0", IN), ConnKind.ROTATION,
                    new Routing.Explicit(List.of("m")), Constraints.NONE));
        }
        ModuleTemplate ten = new ModuleTemplate(1, template.id(), template.displayNameKey(), template.category(), template.requires(),
                template.footprint(), template.ports(), template.nodes(), internal, null, null, Set.of());
        List<PlanNode> nodes = new ArrayList<>();
        for (int i = 0; i < MID_INSTANCES; i++) {
            nodes.add(ExpanderFixtures.instance("i" + i, ten.id(), null, new Anchor.Absolute(new LocalPos(10 * i, 0, 0), Rot.NONE)));
        }
        SemanticPlan plan = ExpanderFixtures.plan(nodes, List.of());
        TemplateBundle bundle = new TemplateBundle(List.of(ten));
        ExpandResult expected = new ReferencePlanExpander(TestParts.registry(), SlotResolver.NONE).expand(plan, bundle, Router.NONE);
        ExpandResult actual = new PlanExpander(TestParts.registry(), SlotResolver.NONE).expand(plan, bundle, Router.NONE);
        assertTrue(expected.issues().isEmpty(), () -> expected.issues().stream().limit(3).toList().toString());
        assertEquals(MID_INSTANCES * MID_CONNECTIONS, expected.plan().routed().size());
        assertEquals(expected, actual);
    }
}
