package io.github.khayashi4337.micradrone.build.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.TestParts;
import io.github.khayashi4337.micradrone.build.compile.PlanCompiler;
import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.BuildLimits;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.ParamValue.BoolV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.IntV;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.Provenance;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.PartCategory;
import io.github.khayashi4337.micradrone.build.parts.VersionRange;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The cost of expanding many module instances. The work that depends on the template alone (its ids, the registry and
 * validator verdict on each part, its hash) is done once per distinct template, and a plan whose expansion would hold
 * more parts than a compile can place is refused before any part is built.
 */
class PlanExpanderScaleTest {
    private static final String TEMPLATE_ID = "mod:pillars";
    private static final String OTHER_TEMPLATE_ID = "mod:other";
    private static final String INSTANCE_PREFIX = "i";
    private static final String KEY_HEIGHT = "height";
    private static final String KEY_BASE = "base";
    private static final int PART_HEIGHT = 3;

    /** The sizes of the measurement: every instance of a template of TEN_PARTS parts, as many instances as fill the limit. */
    private static final int TEN_PARTS = 10;
    private static final int INSTANCES_AT_LIMIT = BuildLimits.MAX_CELLS / TEN_PARTS;
    /** The refusal case: instances of a template so large that building the nodes would need gigabytes. */
    private static final int HUGE_INSTANCES = 100_000;
    private static final int HUGE_PARTS = 40;
    private static final int FIVE_PARTS = 5;
    private static final int FEW = 50;
    private static final int LINE_PARTS = 2;
    private static final String EXTRA_ID = "extra";

    /*
     * Expanding the limit's worth of parts (200,000) took 154 to 274 ms at best and up to 712 ms in the slowest run
     * of the A3 measurements, for template sizes from 2 to 200 parts; the bound is about ten times the slowest. The
     * refusal takes 15 to 32 ms because it builds nothing; the same plan, expanded as before, ran out of memory (512 MB)
     * after 12 s, so the bound cannot be met by building the nodes and stopping later.
     */
    private static final Duration EXPAND_BOUND = Duration.ofSeconds(8);
    private static final Duration REFUSE_BOUND = Duration.ofSeconds(2);

    private final PlanExpander expander = new PlanExpander(TestParts.registry(), SlotResolver.NONE);

    /** A template of {@code parts} pillars: the first is the root, the others hang from it. */
    private static ModuleTemplate pillarTemplate(String id, int parts) {
        Map<String, ParamValue> params = Map.of(KEY_HEIGHT, new IntV(PART_HEIGHT), KEY_BASE, new BoolV(false));
        List<PlanNode> nodes = new ArrayList<>();
        for (int i = 0; i < parts; i++) {
            nodes.add(new PlanNode("p" + i, BuildingParts.ID_PREFIX + "pillar", i == 0 ? null : "p0",
                    new Anchor.Absolute(new LocalPos(i, 0, 0), Rot.NONE), params, Set.of(), ""));
        }
        return new ModuleTemplate(1, id, "k", PartCategory.MODULE, VersionRange.ALWAYS, null, List.of(), nodes, List.of(), null,
                null, Set.of());
    }

    private static PlanNode instance(String templateId, String id, int index) {
        return new PlanNode(id, templateId, null, new Anchor.Absolute(new LocalPos(index, 0, 0), Rot.NONE), Map.of(), Set.of(), "");
    }

    private static List<PlanNode> instances(String templateId, int count) {
        List<PlanNode> nodes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            nodes.add(instance(templateId, INSTANCE_PREFIX + i, i));
        }
        return nodes;
    }

    /** A plan that did not pass the patcher, so that a hundred thousand nodes are made without the patcher's checks. */
    private static SemanticPlan planOf(List<PlanNode> nodes) {
        return new SemanticPlan(SemanticPlan.SCHEMA_VERSION, "scale", 0, null, null, StyleSpec.EMPTY, nodes, List.<Connection>of(),
                null, Provenance.NONE);
    }

    private static TemplateBundle bundleOf(ModuleTemplate... templates) {
        return new TemplateBundle(List.of(templates));
    }

    private static List<String> ids(ExpandResult r) {
        return r.issues().stream().map(Issue::id).toList();
    }

    // ------------------------------------------------------------------ (a) the size of a plan at the limit

    @Test
    void theLimitIsTheCellBudgetTheCompilerHas() {
        assertEquals(200_000, BuildLimits.MAX_CELLS);
        assertEquals(BuildLimits.MAX_CELLS, PlanCompiler.DEFAULT_MAX_CELLS);
        assertEquals(BuildLimits.MAX_CELLS, INSTANCES_AT_LIMIT * TEN_PARTS, "the sizes below fill the limit exactly");
    }

    @Test
    void twentyThousandInstancesOfATenPartTemplateExpandToTwoHundredThousandParts() {
        ModuleTemplate template = pillarTemplate(TEMPLATE_ID, TEN_PARTS);
        SemanticPlan plan = planOf(instances(TEMPLATE_ID, INSTANCES_AT_LIMIT));
        TemplateWork work = new TemplateWork();
        ExpandResult r = assertTimeoutPreemptively(EXPAND_BOUND, () -> {
            long start = System.nanoTime();
            ExpandResult result = expander.expand(plan, bundleOf(template), Router.NONE, work);
            System.out.println("[scale] " + INSTANCES_AT_LIMIT + " x " + TEN_PARTS + " parts: "
                    + (System.nanoTime() - start) / 1_000_000 + " ms");
            return result;
        });
        assertTrue(r.issues().isEmpty(), () -> r.issues().stream().limit(3).toList().toString());
        assertEquals(BuildLimits.MAX_CELLS, r.plan().primitiveNodes().size());
        assertEquals("i0/p0", r.plan().primitiveNodes().get(0).id());
        assertEquals(INSTANCE_PREFIX + (INSTANCES_AT_LIMIT - 1) + "/p" + (TEN_PARTS - 1),
                r.plan().primitiveNodes().get(BuildLimits.MAX_CELLS - 1).id());
        assertEquals(List.of(template.hash()), r.plan().templateHashes());
        assertEquals(1, work.templatesChecked());
        assertEquals(TEN_PARTS, work.partsChecked());
        assertEquals(1, work.templatesHashed());
    }

    // ------------------------------------------------------------------ (b) refusing early

    @Test
    void anExpansionAboveTheLimitIsRefusedFastAndBeforeAnyNodeIsBuilt() {
        ModuleTemplate huge = pillarTemplate(TEMPLATE_ID, HUGE_PARTS);
        SemanticPlan plan = planOf(instances(TEMPLATE_ID, HUGE_INSTANCES));
        TemplateWork work = new TemplateWork();
        ExpandResult r = assertTimeoutPreemptively(REFUSE_BOUND, () -> expander.expand(plan, bundleOf(huge), Router.NONE, work));
        assertNull(r.plan());
        // instance i0..i4999 hold 5,000 x 40 = 200,000 parts, exactly the limit; i5000 is the first to cross it
        assertEquals(List.of("E-OUT-OF-BOUNDS:i5000#cells"), ids(r));
        Issue issue = r.issues().get(0);
        assertEquals(IssueCode.E_OUT_OF_BOUNDS, issue.code());
        assertEquals(List.of("i5000"), issue.subjects());
        assertEquals(Map.of("cells", "200000"), issue.data(), "the same data as the compiler's own budget refusal");
        assertFalse(issue.message().contains("i5000") || issue.message().contains(TEMPLATE_ID),
                "the message does not quote what the plan wrote: " + issue.message());
        assertTrue(issue.message().contains("200000"), issue.message());
        assertEquals(0, work.templatesChecked(), "no template was looked at, so no part can have been built");
        assertEquals(0, work.partsChecked());
        assertEquals(0, work.templatesHashed());
    }

    @Test
    void aPlanExactlyAtTheLimitStillExpands() {
        ModuleTemplate template = pillarTemplate(TEMPLATE_ID, FIVE_PARTS);
        int instances = BuildLimits.MAX_CELLS / FIVE_PARTS;
        ExpandResult r = expander.expand(planOf(instances(TEMPLATE_ID, instances)), bundleOf(template), Router.NONE);
        assertTrue(r.issues().isEmpty(), () -> r.issues().stream().limit(3).toList().toString());
        assertEquals(BuildLimits.MAX_CELLS, r.plan().primitiveNodes().size());
    }

    @Test
    void onePartOverTheLimitIsRefusedAndNamesTheNodeThatCrossed() {
        ModuleTemplate template = pillarTemplate(TEMPLATE_ID, FIVE_PARTS);
        int instances = BuildLimits.MAX_CELLS / FIVE_PARTS;

        // a plain part after the instances: it is the 200,001st part
        List<PlanNode> plainLast = new ArrayList<>(instances(TEMPLATE_ID, instances));
        plainLast.add(TestParts.at(EXTRA_ID, "test:motor", null, 0, 0, 0));
        ExpandResult afterwards = expander.expand(planOf(plainLast), bundleOf(template), Router.NONE);
        assertNull(afterwards.plan());
        assertEquals(List.of("E-OUT-OF-BOUNDS:extra#cells"), ids(afterwards));

        // a plain part first: now the last instance is the one that crosses the limit
        List<PlanNode> plainFirst = new ArrayList<>();
        plainFirst.add(TestParts.at(EXTRA_ID, "test:motor", null, 0, 0, 0));
        plainFirst.addAll(instances(TEMPLATE_ID, instances));
        ExpandResult before = expander.expand(planOf(plainFirst), bundleOf(template), Router.NONE);
        assertNull(before.plan());
        assertEquals(List.of("E-OUT-OF-BOUNDS:" + INSTANCE_PREFIX + (instances - 1) + "#cells"), ids(before));
    }

    @Test
    void partsThatWillNotBeExpandedDoNotCountTowardsTheLimit() {
        ModuleTemplate template = pillarTemplate(TEMPLATE_ID, FIVE_PARTS);
        int instances = BuildLimits.MAX_CELLS / FIVE_PARTS;
        List<PlanNode> nodes = new ArrayList<>(instances(TEMPLATE_ID, instances));
        // a module inside a module instance is refused as one node (E-ANCHOR#parent); it adds no part
        PlanNode inner = new PlanNode("inner", TEMPLATE_ID, INSTANCE_PREFIX + 0,
                new Anchor.Absolute(new LocalPos(0, 0, 0), Rot.NONE), Map.of(), Set.of(), "");
        nodes.add(inner);
        // an instance of a template the bundle does not have adds no part either
        nodes.add(instance("mod:not-in-the-bundle", "ghost", 0));
        ExpandResult r = expander.expand(planOf(nodes), bundleOf(template), Router.NONE);
        assertNull(r.plan());
        assertEquals(List.of("E-ANCHOR:inner#parent", "E-UNKNOWN-PART:ghost"), ids(r),
                "the plan is the size of the limit, not over it: the ordinary issues are what is reported");
    }

    // ------------------------------------------------------------------ (c) work per distinct template

    @Test
    void oneTemplateIsCheckedAndHashedOnceHoweverManyInstancesUseIt() {
        for (int count : new int[]{1, 2, FEW}) {
            TemplateWork work = new TemplateWork();
            ExpandResult r = expander.expand(planOf(instances(TestParts.lineTemplate().id(), count)), TestParts.bundle(), Router.NONE,
                    work);
            assertTrue(r.issues().isEmpty(), () -> r.issues().toString());
            assertEquals(count * LINE_PARTS, r.plan().primitiveNodes().size());
            assertEquals(1, work.templatesChecked(), count + " instances");
            assertEquals(LINE_PARTS, work.partsChecked(), count + " instances: the parts of the template, once");
            assertEquals(1, work.templatesHashed(), count + " instances");
        }
    }

    @Test
    void twoDistinctTemplatesAreEachCheckedAndHashedOnce() {
        ModuleTemplate line = TestParts.lineTemplate();
        ModuleTemplate other = pillarTemplate(OTHER_TEMPLATE_ID, FIVE_PARTS);
        List<PlanNode> nodes = new ArrayList<>();
        for (int i = 0; i < FEW; i++) {
            nodes.add(instance(line.id(), "a" + i, i));
            nodes.add(instance(other.id(), "b" + i, i));
        }
        TemplateWork work = new TemplateWork();
        ExpandResult r = expander.expand(planOf(nodes), bundleOf(line, other), Router.NONE, work);
        assertTrue(r.issues().isEmpty(), () -> r.issues().toString());
        assertEquals(FEW * (LINE_PARTS + FIVE_PARTS), r.plan().primitiveNodes().size());
        assertEquals(2, work.templatesChecked());
        assertEquals(LINE_PARTS + FIVE_PARTS, work.partsChecked());
        assertEquals(2, work.templatesHashed());
        assertEquals(Set.copyOf(List.of(line.hash(), other.hash())), Set.copyOf(r.plan().templateHashes()));
    }

    @Test
    void aTemplateNoInstanceCouldBeExpandedFromIsCheckedForItsIdsButNotItsPartsNorHashed() {
        // an instance on a wall face is refused before its parts are looked at, as it always was; nothing is hashed
        PlanNode onWall = new PlanNode("m1", TestParts.lineTemplate().id(), null,
                new Anchor.OnSurface("wall", Side.OUTER, 0, 0), Map.of(), Set.of(), "");
        TemplateWork work = new TemplateWork();
        ExpandResult r = expander.expand(planOf(List.of(onWall)), TestParts.bundle(), Router.NONE, work);
        assertNull(r.plan());
        assertEquals(List.of("E-ANCHOR:m1#anchor"), ids(r));
        assertEquals(1, work.templatesChecked());
        assertEquals(0, work.partsChecked());
        assertEquals(0, work.templatesHashed());
    }

    @Test
    void thePartsAfterTheFirstOneTheRegistryRefusesAreNeverChecked() {
        // p2 is a part the registry does not have; every instance stops there, so p3 and p4 are never looked at
        ModuleTemplate stops = new ModuleTemplate(1, "mod:stops", "k", PartCategory.MODULE, VersionRange.ALWAYS, null, List.of(),
                List.of(TestParts.at("p0", "test:motor", null, 0, 0, 0), TestParts.at("p1", "test:motor", null, 1, 0, 0),
                        TestParts.at("p2", "x:nope", null, 2, 0, 0), TestParts.at("p3", "test:motor", null, 3, 0, 0),
                        TestParts.at("p4", "test:motor", null, 4, 0, 0)), List.of(), null, null, Set.of());
        TemplateWork work = new TemplateWork();
        ExpandResult r = expander.expand(planOf(instances("mod:stops", FEW)), bundleOf(stops), Router.NONE, work);
        assertNull(r.plan());
        assertEquals(FEW, r.issues().size());
        assertEquals("E-UNKNOWN-PART:i0/p2", r.issues().get(0).id());
        assertEquals(3, work.partsChecked(), "p0, p1 and p2, once each, for all " + FEW + " instances");
    }

    @Test
    void aPartIsNotCheckedForAnInstanceThatIsRefusedBeforeReachingIt() {
        // the building at the front cannot be turned, so a turned instance is refused there, as it always was
        ModuleTemplate hut = new ModuleTemplate(1, "mod:hut", "k", PartCategory.MODULE, VersionRange.ALWAYS, null, List.of(),
                List.of(TestParts.at("shell", "micra:structure", null, 0, 0, 0)), List.of(), null, null, Set.of());
        PlanNode turned = new PlanNode("t1", "mod:hut", null, new Anchor.Absolute(new LocalPos(0, 0, 0), new Rot(1, false)), Map.of(),
                Set.of(), "");
        TemplateWork work = new TemplateWork();
        ExpandResult r = expander.expand(planOf(List.of(turned)), bundleOf(hut), Router.NONE, work);
        assertEquals(List.of("E-ANCHOR:t1#rot"), ids(r));
        assertEquals(1, work.templatesChecked());
        assertEquals(0, work.partsChecked());
        assertEquals(0, work.templatesHashed());
    }

    @Test
    void aBadTemplateIsCheckedOnceAndEveryInstanceStillReportsItsOwnIssues() {
        // "x" is repeated, so no instance can be expanded; each one names the id under its own prefix
        ModuleTemplate twin = new ModuleTemplate(1, "mod:twin", "k", PartCategory.MODULE, VersionRange.ALWAYS, null, List.of(),
                List.of(TestParts.at("x", "test:motor", null, 0, 0, 0), TestParts.at("x", "test:motor", null, 5, 0, 0)), List.of(),
                null, null, Set.of());
        TemplateWork work = new TemplateWork();
        ExpandResult r = expander.expand(planOf(instances("mod:twin", FEW)), bundleOf(twin), Router.NONE, work);
        assertNull(r.plan());
        assertEquals(FEW, r.issues().size());
        assertEquals("E-ID-DUPLICATE:i0/x#template", r.issues().get(0).id());
        assertEquals("E-ID-DUPLICATE:i" + (FEW - 1) + "/x#template", r.issues().get(FEW - 1).id());
        assertEquals(1, work.templatesChecked());
        assertEquals(0, work.partsChecked());
        assertEquals(0, work.templatesHashed());
    }
}
