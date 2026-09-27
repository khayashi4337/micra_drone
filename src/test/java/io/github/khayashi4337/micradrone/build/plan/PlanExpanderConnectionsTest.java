package io.github.khayashi4337.micradrone.build.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.TestParts;
import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.BuildLimits;
import io.github.khayashi4337.micradrone.build.model.ConnKind;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Constraints;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.PortRef;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.Routing;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.parts.PartCategory;
import io.github.khayashi4337.micradrone.build.parts.VersionRange;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The connections of an expanded plan. Every module instance brings the internal connections of its template, so a
 * plan of many instances of a template with many connections would build instances times connections objects; like the
 * parts, the connections are counted first and a plan over the limit is refused before any is built.
 */
class PlanExpanderConnectionsTest {
    private static final String WIRED_ID = "mod:wired";
    private static final String INSTANCE_PREFIX = "i";
    private static final String OWN_PREFIX = "k";
    private static final String PART_ID = "s";
    private static final String NODE_A = "a";

    /** At the limit: this many instances of a template with FOUR_CONNECTIONS connections. */
    private static final int FOUR_CONNECTIONS = 4;
    private static final int INSTANCES_AT_LIMIT = BuildLimits.MAX_EXPANDED_CONNECTIONS / FOUR_CONNECTIONS;
    /** The refusal case: 100,000 instances of a template with a hundred connections (10,000,000 if they were built). */
    private static final int HUGE_INSTANCES = 100_000;
    private static final int HUNDRED_CONNECTIONS = 100;
    /** The mixed case: the plan's own connections and then instances of a template with a thousand. */
    private static final int OWN_CONNECTIONS = 1_000;
    private static final int THOUSAND_CONNECTIONS = 1_000;
    private static final int INSTANCES_AFTER_OWN = (BuildLimits.MAX_EXPANDED_CONNECTIONS - OWN_CONNECTIONS) / THOUSAND_CONNECTIONS;
    private static final int FEW = 50;

    /*
     * About ten times the slowest run measured for the limit's worth of connections (A3b report: 171 to 236 ms before the
     * change and after it). The refusal builds nothing, so it takes milliseconds; the plan expanded as it was before this
     * change runs out of memory (512 MB) on the same input after about three seconds.
     */
    private static final Duration EXPAND_BOUND = Duration.ofSeconds(5);
    private static final Duration REFUSE_BOUND = Duration.ofSeconds(2);

    private final PlanExpander expander = new PlanExpander(TestParts.registry(), SlotResolver.NONE);

    private static Connection selfConnection(String id) {
        return new Connection(id, new PortRef(PART_ID, "out"), new PortRef(PART_ID, "in"), ConnKind.ROTATION,
                new Routing.Explicit(List.of()), Constraints.NONE);
    }

    /** A template of one shaft and {@code connections} connections from its out port to its in port. */
    private static ModuleTemplate wiredTemplate(int connections) {
        List<Connection> internal = new ArrayList<>();
        for (int j = 0; j < connections; j++) {
            internal.add(selfConnection("c" + j));
        }
        return new ModuleTemplate(1, WIRED_ID, "k", PartCategory.MODULE, VersionRange.ALWAYS, null, List.of(),
                List.of(TestParts.at(PART_ID, "test:shaft", null, 0, 0, 0)), internal, null, null, Set.of());
    }

    private static PlanNode instance(String templateId, String id, String parent, int index) {
        return new PlanNode(id, templateId, parent, new Anchor.Absolute(new LocalPos(index, 0, 0), Rot.NONE), Map.of(), Set.of(), "");
    }

    private static List<PlanNode> instances(int count) {
        List<PlanNode> nodes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            nodes.add(instance(WIRED_ID, INSTANCE_PREFIX + i, null, i));
        }
        return nodes;
    }

    /** {@code count} connections of the plan itself, from the out port of the shaft {@code a} to its in port. */
    private static List<Connection> ownConnections(int count) {
        List<Connection> own = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            own.add(new Connection(OWN_PREFIX + i, new PortRef(NODE_A, "out"), new PortRef(NODE_A, "in"), ConnKind.ROTATION,
                    new Routing.Explicit(List.of()), Constraints.NONE));
        }
        return own;
    }

    private static PlanNode shaftA() {
        return TestParts.at(NODE_A, "test:shaft", null, 0, 0, 0);
    }

    private static TemplateBundle bundleOf(ModuleTemplate template) {
        return new TemplateBundle(List.of(template));
    }

    private static List<String> ids(ExpandResult r) {
        return r.issues().stream().map(Issue::id).toList();
    }

    @Test
    void theConnectionLimitIsARulingEqualToTheCellLimit() {
        assertEquals(200_000, BuildLimits.MAX_EXPANDED_CONNECTIONS);
        assertEquals(BuildLimits.MAX_CELLS, BuildLimits.MAX_EXPANDED_CONNECTIONS);
        assertEquals(BuildLimits.MAX_EXPANDED_CONNECTIONS, INSTANCES_AT_LIMIT * FOUR_CONNECTIONS, "the sizes below fill the limit exactly");
        assertEquals(BuildLimits.MAX_EXPANDED_CONNECTIONS, OWN_CONNECTIONS + INSTANCES_AFTER_OWN * THOUSAND_CONNECTIONS);
    }

    @Test
    void instancesOfAConnectedTemplateExpandAtTheLimit() {
        ModuleTemplate template = wiredTemplate(FOUR_CONNECTIONS);
        SemanticPlan plan = ExpanderFixtures.plan(instances(INSTANCES_AT_LIMIT), List.of());
        TemplateWork work = new TemplateWork();
        ExpandResult r = assertTimeoutPreemptively(EXPAND_BOUND, () -> {
            long start = System.nanoTime();
            ExpandResult result = expander.expand(plan, bundleOf(template), Router.NONE, work);
            System.out.println("[conn] " + INSTANCES_AT_LIMIT + " instances x " + FOUR_CONNECTIONS + " connections: "
                    + (System.nanoTime() - start) / 1_000_000 + " ms");
            return result;
        });
        assertTrue(r.issues().isEmpty(), () -> r.issues().stream().limit(3).toList().toString());
        assertEquals(BuildLimits.MAX_EXPANDED_CONNECTIONS, r.plan().routed().size());
        assertEquals(INSTANCES_AT_LIMIT, r.plan().primitiveNodes().size());
        assertEquals("i0/c0", r.plan().routed().get(0).connectionId());
        assertEquals(INSTANCE_PREFIX + (INSTANCES_AT_LIMIT - 1) + "/c" + (FOUR_CONNECTIONS - 1),
                r.plan().routed().get(BuildLimits.MAX_EXPANDED_CONNECTIONS - 1).connectionId());
        assertEquals(1, work.templatesChecked());
        assertEquals(1, work.templatesHashed());
    }

    @Test
    void anExpansionWithTooManyConnectionsIsRefusedFastAndBeforeAnyIsBuilt() {
        ModuleTemplate template = wiredTemplate(HUNDRED_CONNECTIONS);
        SemanticPlan plan = ExpanderFixtures.plan(instances(HUGE_INSTANCES), List.of());
        TemplateWork work = new TemplateWork();
        ExpandResult r = assertTimeoutPreemptively(REFUSE_BOUND, () -> expander.expand(plan, bundleOf(template), Router.NONE, work));
        assertNull(r.plan());
        // i0..i1999 hold 2,000 x 100 = 200,000 connections, exactly the limit; i2000 is the first to cross it. The
        // parts (100,000 of them) are far below their own limit, so it is the connections that refuse the plan.
        assertEquals(List.of("E-OUT-OF-BOUNDS:i2000#connections"), ids(r));
        Issue issue = r.issues().get(0);
        assertEquals(IssueCode.E_OUT_OF_BOUNDS, issue.code());
        assertEquals(List.of("i2000"), issue.subjects());
        assertEquals(Map.of("connections", "200000"), issue.data());
        assertFalse(issue.message().contains("i2000") || issue.message().contains(WIRED_ID),
                "the message does not quote what the plan wrote: " + issue.message());
        assertTrue(issue.message().contains("200000"), issue.message());
        assertEquals(0, work.templatesChecked(), "no template was looked at, so nothing can have been built");
        assertEquals(0, work.partsChecked());
        assertEquals(0, work.templatesHashed());
    }

    @Test
    void theCountStartsWithThePlansOwnConnectionsAndTheInstanceThatCrossesIsNamed() {
        ModuleTemplate template = wiredTemplate(THOUSAND_CONNECTIONS);
        // the plan's own connections count first: OWN_CONNECTIONS + INSTANCES_AFTER_OWN x 1,000 is exactly the limit
        List<PlanNode> nodes = new ArrayList<>();
        nodes.add(shaftA());
        nodes.addAll(instances(INSTANCES_AFTER_OWN));
        ExpandResult atLimit = expander.expand(ExpanderFixtures.plan(nodes, ownConnections(OWN_CONNECTIONS)), bundleOf(template),
                Router.NONE);
        assertTrue(atLimit.issues().isEmpty(), () -> atLimit.issues().stream().limit(3).toList().toString());
        assertEquals(BuildLimits.MAX_EXPANDED_CONNECTIONS, atLimit.plan().routed().size());
        // the plan's own come first in the routed list, then the instances' in plan order
        assertEquals("k0", atLimit.plan().routed().get(0).connectionId());
        assertEquals("i0/c0", atLimit.plan().routed().get(OWN_CONNECTIONS).connectionId());

        // one more instance takes it past the limit, and it is the one named
        List<PlanNode> oneMore = new ArrayList<>(nodes);
        oneMore.add(instance(WIRED_ID, INSTANCE_PREFIX + INSTANCES_AFTER_OWN, null, INSTANCES_AFTER_OWN));
        ExpandResult over = expander.expand(ExpanderFixtures.plan(oneMore, ownConnections(OWN_CONNECTIONS)), bundleOf(template), Router.NONE);
        assertNull(over.plan());
        assertEquals(List.of("E-OUT-OF-BOUNDS:i" + INSTANCES_AFTER_OWN + "#connections"), ids(over));
    }

    @Test
    void thePlansOwnConnectionsAloneCanCrossTheLimitAndTheConnectionThatCrossedIsNamed() {
        int limit = BuildLimits.MAX_EXPANDED_CONNECTIONS;
        SemanticPlan plan = ExpanderFixtures.plan(List.of(shaftA()), ownConnections(limit + 1));
        ExpandResult r = assertTimeoutPreemptively(REFUSE_BOUND, () -> expander.expand(plan, TemplateBundle.EMPTY, Router.NONE));
        assertNull(r.plan());
        // k0..k199999 are the limit's worth; k200000 is the one over
        assertEquals(List.of("E-OUT-OF-BOUNDS:k" + limit + "#connections"), ids(r));
        assertEquals(List.of("k" + limit), r.issues().get(0).subjects());
    }

    @Test
    void connectionsOfInstancesThatWillNotBeExpandedDoNotCountTowardsTheLimit() {
        ModuleTemplate template = wiredTemplate(FOUR_CONNECTIONS);
        List<PlanNode> nodes = new ArrayList<>(instances(INSTANCES_AT_LIMIT));
        // an instance inside another instance is refused as one node, and one of a template the bundle lacks has no connections
        nodes.add(instance(WIRED_ID, "inner", INSTANCE_PREFIX + 0, 0));
        nodes.add(instance("mod:not-in-the-bundle", "ghost", null, 0));
        ExpandResult r = expander.expand(ExpanderFixtures.plan(nodes, List.of()), bundleOf(template), Router.NONE);
        assertNull(r.plan());
        assertEquals(List.of("E-ANCHOR:inner#parent", "E-UNKNOWN-PART:ghost"), ids(r),
                "the plan holds the limit's worth of connections, not more: the ordinary issues are what is reported");
    }

    @Test
    void whenOneNodeCrossesBothLimitsAtOnceTheCellsIssueIsTheOne() {
        // a template of 100,001 parts and 100,001 connections: one instance is under both limits, the second is over both
        int size = BuildLimits.MAX_CELLS / 2 + 1;
        List<PlanNode> parts = new ArrayList<>();
        List<Connection> internal = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            parts.add(TestParts.at("p" + i, "test:shaft", null, i, 0, 0));
            internal.add(new Connection("c" + i, new PortRef("p0", "out"), new PortRef("p0", "in"), ConnKind.ROTATION,
                    new Routing.Explicit(List.of()), Constraints.NONE));
        }
        ModuleTemplate big = new ModuleTemplate(1, WIRED_ID, "k", PartCategory.MODULE, VersionRange.ALWAYS, null, List.of(), parts,
                internal, null, null, Set.of());
        ExpandResult r = expander.expand(ExpanderFixtures.plan(instances(2), List.of()), bundleOf(big), Router.NONE);
        assertNull(r.plan());
        assertEquals(List.of("E-OUT-OF-BOUNDS:i1#cells"), ids(r));
    }

    @Test
    void aFewConnectedInstancesStillExpandExactlyAsBefore() {
        ModuleTemplate template = wiredTemplate(FOUR_CONNECTIONS);
        ExpandResult r = expander.expand(ExpanderFixtures.plan(instances(FEW), List.of()), bundleOf(template), Router.NONE);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        assertEquals(FEW * FOUR_CONNECTIONS, r.plan().routed().size());
    }
}
