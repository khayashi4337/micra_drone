package io.github.khayashi4337.micradrone.build.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.TestParts;
import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.ConnKind;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Constraints;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.ParamValue.EnumV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.IntV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.StrV;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.PlanOp;
import io.github.khayashi4337.micradrone.build.model.PlanPatch;
import io.github.khayashi4337.micradrone.build.model.PortRef;
import io.github.khayashi4337.micradrone.build.model.Provenance;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.Routing;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.PartCategory;
import io.github.khayashi4337.micradrone.build.parts.VersionRange;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PlanExpanderTest {
    private static final String LINE_MODULE = "mod:test_line";
    private static final Duration WALK_TIMEOUT = Duration.ofSeconds(10);

    private final PlanExpander expander = new PlanExpander(TestParts.registry(), SlotResolver.NONE);
    /** A resolver that finds every slot, so a refusal cannot be blamed on a missing slot. */
    private final PlanExpander slotted = new PlanExpander(TestParts.registry(), id -> Optional.of(new LocalPos(5, 0, 5)));

    private SemanticPlan plan(List<PlanOp> ops) {
        return planWith(TestParts.bundle(), ops);
    }

    private static SemanticPlan planWith(TemplateBundle bundle, List<PlanOp> ops) {
        PatchResult r = new PlanPatcher(TestParts.registry(), bundle).apply(SemanticPlan.empty("p"), new PlanPatch("p", 0, "t", ops));
        assertTrue(r.ok(), r.issues().toString());
        return r.plan();
    }

    /** A plan that did not pass the patcher: the expander must hold up against what a client could hand-build. */
    private static SemanticPlan handBuilt(List<PlanNode> nodes, List<Connection> connections) {
        return new SemanticPlan(SemanticPlan.SCHEMA_VERSION, "hand", 0, null, null, StyleSpec.EMPTY, nodes, connections,
                null, Provenance.NONE);
    }

    private static PlanNode instanceOf(String type, String id, String parent, Anchor anchor) {
        return new PlanNode(id, type, parent, anchor, Map.of(), Set.of(), "");
    }

    private static PlanNode module(String id, String parent, Anchor anchor) {
        return instanceOf(LINE_MODULE, id, parent, anchor);
    }

    private static PlanNode wall(String id, String parent) {
        return new PlanNode(id, BuildingParts.WALL, parent, abs(0, 0, 0, 0), Map.of("side", new StrV("north")), Set.of(), "");
    }

    private static ModuleTemplate template(String id, List<PlanNode> nodes, List<Connection> internal) {
        return new ModuleTemplate(1, id, "k", PartCategory.MODULE, VersionRange.ALWAYS, null, List.of(), nodes, internal, null,
                null, Set.of());
    }

    private static Connection explicit(String id, String fromNode, String fromPort, String toNode, String toPort, String... via) {
        return new Connection(id, new PortRef(fromNode, fromPort), new PortRef(toNode, toPort), ConnKind.ROTATION,
                new Routing.Explicit(List.of(via)), Constraints.NONE);
    }

    private static Anchor.Absolute abs(int u, int v, int w, int turns) {
        return new Anchor.Absolute(new LocalPos(u, v, w), new Rot(turns, false));
    }

    private static PlanNode find(ExpandedPlan plan, String id) {
        return plan.primitiveNodes().stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow();
    }

    private static List<String> ids(ExpandResult r) {
        return r.issues().stream().map(Issue::id).toList();
    }

    @Test
    void aPlanWithoutModulesKeepsItsNodes() {
        SemanticPlan plan = plan(List.of(new PlanOp.AddNode(TestParts.at("m", "test:motor", null, 1, 0, 2))));
        ExpandResult r = expander.expand(plan, TemplateBundle.EMPTY, Router.NONE);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        assertEquals(plan.nodes(), r.plan().primitiveNodes());
        assertEquals(List.of(), r.plan().templateHashes());
        assertSame(plan, r.plan().source());
    }

    @Test
    void aModuleBecomesItsPartsWithPrefixedIdsAndAComposedTransform() {
        SemanticPlan plan = plan(List.of(new PlanOp.AddNode(module("line-1", null, abs(10, 0, 20, 1)))));
        ExpandResult r = expander.expand(plan, TestParts.bundle(), Router.NONE);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        ExpandedPlan e = r.plan();
        assertEquals(2, e.primitiveNodes().size());
        // motor: template root at (2,0,1); one clockwise turn maps (u,w)=(2,1) to (1,-2); plus the instance position
        PlanNode motor = find(e, "line-1/motor");
        assertEquals(null, motor.parent());
        assertEquals(new Anchor.Absolute(new LocalPos(11, 0, 18), new Rot(1, false)), motor.anchor());
        // shaft: child of motor at (1,0,0); rotated by the instance rotation only
        PlanNode shaft = find(e, "line-1/shaft");
        assertEquals("line-1/motor", shaft.parent());
        assertEquals(new Anchor.Absolute(new LocalPos(0, 0, -1), new Rot(1, false)), shaft.anchor());
        assertEquals(List.of(TestParts.lineTemplate().hash()), e.templateHashes());
        assertEquals(1, e.routed().size(), "the template's internal explicit connection is kept");
        assertEquals("line-1/link", e.routed().get(0).connectionId());
    }

    @Test
    void theInstanceKeepsItsParentForTheTemplateRoots() {
        SemanticPlan plan = plan(List.of(
                new PlanOp.AddNode(TestParts.at("room", "micra:structure", null, 0, 0, 0)),
                new PlanOp.AddNode(module("line-1", "room", abs(1, 0, 1, 0)))));
        ExpandedPlan e = expander.expand(plan, TestParts.bundle(), Router.NONE).plan();
        assertEquals("room", find(e, "line-1/motor").parent());
        assertEquals(new Anchor.Absolute(new LocalPos(3, 0, 2), Rot.NONE), find(e, "line-1/motor").anchor());
    }

    @Test
    void missingTemplateAndBadInstanceAnchorsAreErrors() {
        SemanticPlan plan = plan(List.of(new PlanOp.AddNode(module("line-1", null, abs(0, 0, 0, 0)))));
        ExpandResult missing = expander.expand(plan, TemplateBundle.EMPTY, Router.NONE);
        assertNull(missing.plan());
        assertEquals(IssueCode.E_UNKNOWN_PART, missing.issues().get(0).code());

        SemanticPlan slotPlan = plan(List.of(new PlanOp.AddNode(module("line-2", null, new Anchor.InSlot("slot-a", Rot.NONE)))));
        ExpandResult noSlots = expander.expand(slotPlan, TestParts.bundle(), Router.NONE);
        assertEquals(IssueCode.E_ANCHOR, noSlots.issues().get(0).code(), "slots need the building analysis (P6)");
        ExpandResult resolved = slotted.expand(slotPlan, TestParts.bundle(), Router.NONE);
        assertTrue(resolved.issues().isEmpty(), resolved.issues().toString());
        assertEquals(new Anchor.Absolute(new LocalPos(7, 0, 6), Rot.NONE), find(resolved.plan(), "line-2/motor").anchor());
    }

    @Test
    void aModuleInstanceCannotSitOnAWallFace() {
        SemanticPlan plan = plan(List.of(
                new PlanOp.AddNode(TestParts.at("room", "micra:structure", null, 0, 0, 0)),
                new PlanOp.AddNode(wall("wall-n", "room")),
                new PlanOp.AddNode(module("line-1", null, new Anchor.OnSurface("wall-n", Side.OUTER, 1, 1)))));
        ExpandResult r = expander.expand(plan, TestParts.bundle(), Router.NONE);
        assertNull(r.plan());
        assertEquals(List.of("E-ANCHOR:line-1#anchor"), ids(r));
    }

    @Test
    void aSlotInstanceTakesItsPositionAndRotationFromTheSlotAnchor() {
        SemanticPlan plan = plan(List.of(new PlanOp.AddNode(module("line-1", null, new Anchor.InSlot("slot-a", new Rot(1, false))))));
        ExpandResult r = slotted.expand(plan, TestParts.bundle(), Router.NONE);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        // motor (2,0,1) turned once is (1,0,-2); the slot is at (5,0,5)
        assertEquals(new Anchor.Absolute(new LocalPos(6, 0, 3), new Rot(1, false)), find(r.plan(), "line-1/motor").anchor());
        assertEquals("E-ANCHOR:line-1#slot", ids(expander.expand(plan, TestParts.bundle(), Router.NONE)).get(0));
    }

    @Test
    void aSlotInstanceCannotHaveAParent() {
        SemanticPlan plan = plan(List.of(
                new PlanOp.AddNode(TestParts.at("room", "micra:structure", null, 0, 0, 0)),
                new PlanOp.AddNode(module("line-1", "room", new Anchor.InSlot("slot-a", Rot.NONE)))));
        ExpandResult r = slotted.expand(plan, TestParts.bundle(), Router.NONE);
        assertNull(r.plan());
        assertEquals(List.of("E-ANCHOR:line-1#parent"), ids(r));
    }

    @Test
    void aRotatedModuleCannotContainABuilding() {
        ModuleTemplate withBuilding = template("mod:hut", List.of(TestParts.at("shell", "micra:structure", null, 0, 0, 0)), List.of());
        TemplateBundle bundle = new TemplateBundle(List.of(withBuilding));
        PlanPatcher p = new PlanPatcher(TestParts.registry(), bundle);
        SemanticPlan rotated = p.apply(SemanticPlan.empty("p"), new PlanPatch("p", 0, "t", List.of(
                new PlanOp.AddNode(new PlanNode("h", "mod:hut", null, abs(0, 0, 0, 1), Map.of(), Set.of(), ""))))).plan();
        ExpandResult r = expander.expand(rotated, bundle, Router.NONE);
        assertNull(r.plan());
        assertEquals(List.of("E-ANCHOR:h#rot"), ids(r));
        SemanticPlan straight = p.apply(SemanticPlan.empty("p"), new PlanPatch("p", 0, "t", List.of(
                new PlanOp.AddNode(new PlanNode("h", "mod:hut", null, abs(3, 0, 3, 0), Map.of(), Set.of(), ""))))).plan();
        ExpandResult unturned = expander.expand(straight, bundle, Router.NONE);
        assertTrue(unturned.issues().isEmpty(), unturned.issues().toString());
        assertEquals(new Anchor.Absolute(new LocalPos(3, 0, 3), Rot.NONE), find(unturned.plan(), "h/shell").anchor());
        assertTrue(BuildingParts.ROTATION_UNSUPPORTED.contains("micra:structure"));
        assertFalse(BuildingParts.ROTATION_UNSUPPORTED.contains("micra:pillar"));
    }

    @Test
    void aMirrorAloneCountsAsATurnForBuildings() {
        ModuleTemplate withBuilding = template("mod:hut", List.of(TestParts.at("shell", "micra:structure", null, 0, 0, 0)), List.of());
        TemplateBundle bundle = new TemplateBundle(List.of(withBuilding));
        SemanticPlan mirrored = planWith(bundle, List.of(new PlanOp.AddNode(
                instanceOf("mod:hut", "h", null, new Anchor.Absolute(new LocalPos(0, 0, 0), new Rot(0, true))))));
        ExpandResult r = expander.expand(mirrored, bundle, Router.NONE);
        assertNull(r.plan());
        assertEquals(List.of("E-ANCHOR:h#rot"), ids(r));
    }

    @Test
    void aMirroredInstanceMirrorsPositionsAndComposesRotationsOuterOverInner() {
        SemanticPlan plan = plan(List.of(new PlanOp.AddNode(module("line-1", null,
                new Anchor.Absolute(new LocalPos(0, 0, 0), new Rot(1, true))))));
        ExpandedPlan e = expander.expand(plan, TestParts.bundle(), Router.NONE).plan();
        // mirror first: (2,0,1) -> (-2,0,1); then one clockwise turn: (u,w) -> (w,-u) = (1,2)
        assertEquals(new Anchor.Absolute(new LocalPos(1, 0, 2), new Rot(1, true)), find(e, "line-1/motor").anchor());
        // the child is transformed by the rotation alone: (1,0,0) -> (-1,0,0) -> (0,0,1)
        assertEquals(new Anchor.Absolute(new LocalPos(0, 0, 1), new Rot(1, true)), find(e, "line-1/shaft").anchor());

        // a template part that is itself turned: mirror(outer) after one turn(inner) is three turns, mirrored
        ModuleTemplate turnedPart = template("mod:turned", List.of(new PlanNode("n", "test:motor", null,
                new Anchor.Absolute(new LocalPos(0, 0, 0), new Rot(1, false)), Map.of(), Set.of(), "")), List.of());
        TemplateBundle bundle = new TemplateBundle(List.of(turnedPart));
        SemanticPlan mirroredInstance = planWith(bundle, List.of(new PlanOp.AddNode(instanceOf("mod:turned", "t", null,
                new Anchor.Absolute(new LocalPos(0, 0, 0), new Rot(0, true))))));
        ExpandedPlan composed = expander.expand(mirroredInstance, bundle, Router.NONE).plan();
        assertEquals(new Anchor.Absolute(new LocalPos(0, 0, 0), new Rot(3, true)), find(composed, "t/n").anchor());
    }

    @Test
    void surfaceAnchorsInsideATemplateFollowTheInstancePrefixAndParametersAreStoredTyped() {
        ModuleTemplate shed = template("mod:shed", List.of(
                TestParts.at("hall", "micra:structure", null, 0, 0, 0),
                wall("wall-n", "hall"),
                new PlanNode("door-1", "micra:door", "hall", new Anchor.OnSurface("wall-n", Side.OUTER, 2, 0), Map.of(), Set.of(), "")),
                List.of());
        TemplateBundle bundle = new TemplateBundle(List.of(shed));
        SemanticPlan plan = planWith(bundle, List.of(new PlanOp.AddNode(instanceOf("mod:shed", "s1", null, abs(4, 0, 4, 0)))));
        ExpandResult r = expander.expand(plan, bundle, Router.NONE);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        ExpandedPlan e = r.plan();
        assertEquals(new Anchor.OnSurface("s1/wall-n", Side.OUTER, 2, 0), find(e, "s1/door-1").anchor());
        assertEquals("s1/hall", find(e, "s1/door-1").parent());
        assertEquals("s1/hall", find(e, "s1/wall-n").parent());
        assertEquals(new Anchor.Absolute(new LocalPos(4, 0, 4), Rot.NONE), find(e, "s1/hall").anchor());
        assertEquals(new Anchor.Absolute(new LocalPos(0, 0, 0), Rot.NONE), find(e, "s1/wall-n").anchor());
        assertEquals(Map.of("side", new EnumV("north")), find(e, "s1/wall-n").params(), "the loose text is typed by the registry");
    }

    @Test
    void aSlotAnchorInsideATemplateIsRefusedEvenWhenTheSlotWouldResolve() {
        ModuleTemplate slotty = template("mod:slotty", List.of(new PlanNode("n", "test:motor", null,
                new Anchor.InSlot("slot-a", Rot.NONE), Map.of(), Set.of(), "")), List.of());
        TemplateBundle bundle = new TemplateBundle(List.of(slotty));
        SemanticPlan plan = planWith(bundle, List.of(new PlanOp.AddNode(instanceOf("mod:slotty", "m1", null, abs(0, 0, 0, 0)))));
        ExpandResult r = slotted.expand(plan, bundle, Router.NONE);
        assertNull(r.plan());
        assertEquals(List.of("E-ANCHOR:m1/n#anchor"), ids(r));
    }

    @Test
    void oversizedTemplatePositionsAreRefusedInsteadOfWrappingAround() {
        ModuleTemplate farRoot = template("mod:far-root",
                List.of(TestParts.at("n", "test:motor", null, Integer.MAX_VALUE, 0, 0)), List.of());
        ModuleTemplate farChild = template("mod:far-child", List.of(TestParts.at("r", "test:motor", null, 0, 0, 0),
                TestParts.at("c", "test:motor", "r", 0, 0, Integer.MIN_VALUE)), List.of());
        TemplateBundle bundle = new TemplateBundle(List.of(farRoot, farChild));
        SemanticPlan plan = planWith(bundle, List.of(
                new PlanOp.AddNode(instanceOf("mod:far-root", "m1", null, abs(10, 0, 0, 0))),
                new PlanOp.AddNode(instanceOf("mod:far-child", "m2", null, abs(0, 0, 0, 0)))));
        ExpandResult r = expander.expand(plan, bundle, Router.NONE);
        assertNull(r.plan());
        assertEquals(List.of("E-ANCHOR:m1/n#anchor", "E-ANCHOR:m2/c#anchor"), ids(r));

        // two huge values that would add up to a small int if the sum were taken in int
        SemanticPlan farInstance = handBuilt(List.of(instanceOf("mod:far-root", "m3", null, abs(Integer.MAX_VALUE, 0, 0, 0))), List.of());
        assertEquals(List.of("E-ANCHOR:m3/n#anchor"), ids(expander.expand(farInstance, bundle, Router.NONE)));
    }

    @Test
    void explicitConnectionsRecordThePathOfTheirViaNodes() {
        SemanticPlan plan = plan(List.of(
                new PlanOp.AddNode(TestParts.at("m", "test:motor", null, 0, 0, 0)),
                new PlanOp.AddNode(TestParts.at("s1", "test:shaft", null, 1, 0, 0)),
                new PlanOp.AddNode(TestParts.at("s2", "test:shaft", null, 2, 0, 0)),
                new PlanOp.AddNode(TestParts.at("p", "test:press", null, 3, 0, 0)),
                new PlanOp.AddConnection(new Connection("c-1", new PortRef("m", "out"), new PortRef("p", "power_in"),
                        ConnKind.ROTATION, new Routing.Explicit(List.of("s1", "s2")), Constraints.NONE))));
        ExpandResult r = expander.expand(plan, TemplateBundle.EMPTY, Router.NONE);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        RoutedConnection routed = r.plan().routed().get(0);
        assertEquals("c-1", routed.connectionId());
        assertEquals(List.of(new LocalPos(1, 0, 0), new LocalPos(2, 0, 0)), routed.path());
        assertEquals(List.of(), routed.intermediateNodes());
    }

    @Test
    void aViaNodeWithoutAnOriginIsSkippedInThePath() {
        SemanticPlan plan = plan(List.of(
                new PlanOp.AddNode(TestParts.at("room", "micra:structure", null, 0, 0, 0)),
                new PlanOp.AddNode(wall("wall-n", "room")),
                new PlanOp.AddNode(TestParts.at("m", "test:motor", null, 0, 0, 0)),
                new PlanOp.AddNode(instanceOf("test:shaft", "s1", null, new Anchor.OnSurface("wall-n", Side.OUTER, 0, 0))),
                new PlanOp.AddNode(TestParts.at("s2", "test:shaft", null, 2, 0, 0)),
                new PlanOp.AddNode(TestParts.at("p", "test:press", null, 3, 0, 0)),
                new PlanOp.AddConnection(explicit("c-1", "m", "out", "p", "power_in", "s1", "s2"))));
        ExpandResult r = expander.expand(plan, TemplateBundle.EMPTY, Router.NONE);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        assertEquals(List.of(new LocalPos(2, 0, 0)), r.plan().routed().get(0).path(), "s1 sits on a wall face: no origin here");
    }

    @Test
    void connectionsNeedExistingNodesAndPorts() {
        SemanticPlan plan = handBuilt(List.of(
                TestParts.at("m", "test:motor", null, 0, 0, 0),
                TestParts.at("p", "test:press", null, 3, 0, 0),
                module("line-1", null, abs(0, 0, 5, 0))), List.of(
                explicit("c-1", "m", "nope", "p", "power_in"),
                explicit("c-2", "ghost", "out", "p", "power_in"),
                explicit("c-3", "line-1", "nope", "p", "power_in"),
                explicit("c-4", "ghost", "out", "void", "in"),
                explicit("c-5", "m", "out", "p", "power_in"),
                new Connection("c-6", new PortRef("m", "nope"), new PortRef("p", "power_in"), ConnKind.ROTATION, Routing.AUTO,
                        Constraints.NONE)));
        ExpandResult r = expander.expand(plan, TestParts.bundle(), Router.NONE);
        assertNull(r.plan());
        assertEquals(List.of("E-CONN-INVALID:c-1#m.nope", "E-CONN-INVALID:c-2#ghost.out", "E-CONN-INVALID:c-3#line-1.nope",
                "E-CONN-INVALID:c-4#ghost.out", "E-CONN-INVALID:c-4#void.in", "E-CONN-INVALID:c-6#m.nope"), ids(r),
                "a connection with a bad end is not routed on, so c-6 gets no E-NO-ROUTE");
        assertEquals(List.of("c-1"), r.issues().get(0).subjects());
    }

    @Test
    void viaNodesMustBePartsOfThePlan() {
        SemanticPlan plan = handBuilt(List.of(
                TestParts.at("m", "test:motor", null, 0, 0, 0),
                TestParts.at("p", "test:press", null, 3, 0, 0),
                module("line-1", null, abs(0, 0, 5, 0))), List.of(
                explicit("c-1", "m", "out", "p", "power_in", "ghost"),
                explicit("c-2", "m", "out", "p", "power_in", "line-1")));
        ExpandResult r = expander.expand(plan, TestParts.bundle(), Router.NONE);
        assertNull(r.plan());
        assertEquals(List.of("E-CONN-INVALID:c-1#via:ghost", "E-CONN-INVALID:c-2#via:line-1"), ids(r));
    }

    @Test
    void autoConnectionsAreRefusedUntilARouterExists() {
        SemanticPlan plan = plan(List.of(
                new PlanOp.AddNode(TestParts.at("m", "test:motor", null, 0, 0, 0)),
                new PlanOp.AddNode(TestParts.at("p", "test:press", null, 3, 0, 0)),
                new PlanOp.AddConnection(new Connection("c-1", new PortRef("m", "out"), new PortRef("p", "power_in"),
                        ConnKind.ROTATION, Routing.AUTO, Constraints.NONE))));
        ExpandResult r = expander.expand(plan, TemplateBundle.EMPTY, Router.NONE);
        assertNull(r.plan());
        assertEquals(List.of(IssueCode.E_NO_ROUTE), r.issues().stream().map(i -> i.code()).toList());
        assertEquals(List.of("c-1"), r.issues().get(0).subjects());

        Router fake = (pl, c) -> Optional.of(new RoutedConnection(c.id(), List.of(TestParts.at("auto-1", "test:shaft", null, 1, 0, 0)),
                List.of(new LocalPos(1, 0, 0))));
        ExpandResult routed = expander.expand(plan, TemplateBundle.EMPTY, fake);
        assertTrue(routed.issues().isEmpty(), routed.issues().toString());
        assertEquals("auto-1", routed.plan().routed().get(0).intermediateNodes().get(0).id());
    }

    @Test
    void connectionsToAModulePortNeedThatPort() {
        SemanticPlan plan = plan(List.of(
                new PlanOp.AddNode(module("line-1", null, abs(0, 0, 0, 0))),
                new PlanOp.AddNode(TestParts.at("s", "test:shaft", null, 5, 0, 0)),
                new PlanOp.AddConnection(new Connection("c-1", new PortRef("line-1", "out"), new PortRef("s", "in"),
                        ConnKind.ROTATION, new Routing.Explicit(List.of()), Constraints.NONE))));
        ExpandResult ok = expander.expand(plan, TestParts.bundle(), Router.NONE);
        assertTrue(ok.issues().isEmpty(), ok.issues().toString());
        // the plan's connection first, then the instance's internal one; both are explicit with no via parts
        assertEquals(List.of(new RoutedConnection("c-1", List.of(), List.of()), new RoutedConnection("line-1/link", List.of(), List.of())),
                ok.plan().routed());
    }

    @Test
    void aTemplatesInternalConnectionsMoveIntoTheInstancesNamespace() {
        Constraints avoidingS1 = new Constraints(null, Set.of("s1"), null, Set.of());
        ModuleTemplate chain = template("mod:chain", List.of(
                TestParts.at("motor", "test:motor", null, 0, 0, 0),
                TestParts.at("s1", "test:shaft", "motor", 1, 0, 0),
                TestParts.at("s2", "test:shaft", "motor", 2, 0, 0)), List.of(
                explicit("run", "motor", "out", "s2", "in", "s1"),
                new Connection("auto", new PortRef("motor", "out"), new PortRef("s2", "in"), ConnKind.ROTATION, Routing.AUTO, avoidingS1)));
        TemplateBundle bundle = new TemplateBundle(List.of(chain));
        SemanticPlan plan = planWith(bundle, List.of(new PlanOp.AddNode(instanceOf("mod:chain", "t1", null, abs(10, 0, 0, 0)))));
        List<Connection> seen = new ArrayList<>();
        Router recording = (pl, c) -> {
            seen.add(c);
            return Optional.of(new RoutedConnection(c.id(), List.of(), List.of()));
        };
        ExpandResult r = expander.expand(plan, bundle, recording);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        assertEquals(List.of("t1/run", "t1/auto"), r.plan().routed().stream().map(RoutedConnection::connectionId).toList());
        assertEquals(List.of(new LocalPos(11, 0, 0)), r.plan().routed().get(0).path(), "via s1 became t1/s1, which sits at 10+1");
        assertEquals(1, seen.size());
        Connection auto = seen.get(0);
        assertEquals(new PortRef("t1/motor", "out"), auto.from());
        assertEquals(new PortRef("t1/s2", "in"), auto.to());
        assertEquals(Set.of("t1/s1"), auto.constraints().avoidNodeIds(), "avoided nodes are template-local ids too");
    }

    @Test
    void templateHashesAreSortedAndDistinct() {
        ModuleTemplate line = TestParts.lineTemplate();
        ModuleTemplate solo = template("mod:solo", List.of(TestParts.at("motor", "test:motor", null, 0, 0, 0)), List.of());
        TemplateBundle bundle = new TemplateBundle(List.of(line, solo));
        // the template with the larger hash is placed first, so the order of use and dictionary order disagree
        List<ModuleTemplate> largerFirst = List.of(line, solo).stream()
                .sorted(Comparator.comparing(ModuleTemplate::hash).reversed()).toList();
        ModuleTemplate larger = largerFirst.get(0);
        ModuleTemplate smaller = largerFirst.get(1);
        SemanticPlan plan = planWith(bundle, List.of(
                new PlanOp.AddNode(instanceOf(larger.id(), "a-1", null, abs(0, 0, 0, 0))),
                new PlanOp.AddNode(instanceOf(larger.id(), "a-2", null, abs(10, 0, 0, 0))),
                new PlanOp.AddNode(instanceOf(smaller.id(), "b-1", null, abs(20, 0, 0, 0)))));
        ExpandedPlan e = expander.expand(plan, bundle, Router.NONE).plan();
        assertEquals(List.of(smaller.hash(), larger.hash()), e.templateHashes());
        assertNotEquals(larger.hash(), smaller.hash());
        Set<String> primitiveIds = e.primitiveNodes().stream().map(PlanNode::id).collect(Collectors.toSet());
        assertTrue(primitiveIds.containsAll(Set.of("a-1/motor", "a-2/motor", "b-1/motor")),
                "every instance has its own copy of each part: " + primitiveIds);
    }

    @Test
    void templateHashCoversStructureAndVerifyAgainstChecksIt() {
        ModuleTemplate a = TestParts.lineTemplate();
        ModuleTemplate moved = new ModuleTemplate(a.schemaVersion(), a.id(), a.displayNameKey(), a.category(), a.requires(),
                a.footprint(), a.ports(), List.of(TestParts.at("motor", "test:motor", null, 9, 0, 1), a.nodes().get(1)),
                a.internal(), a.stats(), a.verification(), a.tags());
        assertNotEquals(a.hash(), moved.hash());
        assertEquals(a.hash(), a.hash());
        // stats and verification are metadata, not part of the hash
        ModuleTemplate restamped = new ModuleTemplate(a.schemaVersion(), a.id(), a.displayNameKey(), a.category(), a.requires(),
                a.footprint(), a.ports(), a.nodes(), a.internal(),
                new TemplateStats(16, 512, Map.of("x", 1.0)), new Verification(VerificationOrigin.BUNDLED_CI, "v1", "h", 1L), a.tags());
        assertEquals(a.hash(), restamped.hash());

        TemplateBundle bundle = new TemplateBundle(List.of(a));
        assertTrue(bundle.verifyAgainst(Map.of(a.id(), a.hash())).isEmpty());
        assertEquals(IssueCode.E_TEMPLATE_UNVERIFIED, bundle.verifyAgainst(Map.of(a.id(), "0".repeat(64))).get(0).code());
        assertEquals(IssueCode.E_TEMPLATE_UNVERIFIED, bundle.verifyAgainst(Map.of()).get(0).code());
    }

    @Test
    void templatePartsAreValidatedLikeAnyOtherPart() {
        ModuleTemplate badParam = template("mod:bad", List.of(new PlanNode("p", "micra:pillar", null,
                new Anchor.Absolute(new LocalPos(0, 0, 0), Rot.NONE), Map.of("height", new IntV(999)), Set.of(), "")), List.of());
        ModuleTemplate nested = template("mod:nest", List.of(TestParts.at("inner", "mod:test_line", null, 0, 0, 0)), List.of());
        TemplateBundle bundle = new TemplateBundle(List.of(badParam, nested, TestParts.lineTemplate()));
        PlanPatcher p = new PlanPatcher(TestParts.registry(), bundle);
        SemanticPlan withBad = p.apply(SemanticPlan.empty("p"), new PlanPatch("p", 0, "t", List.of(
                new PlanOp.AddNode(new PlanNode("x", "mod:bad", null, abs(0, 0, 0, 0), Map.of(), Set.of(), ""))))).plan();
        ExpandResult bad = expander.expand(withBad, bundle, Router.NONE);
        assertNull(bad.plan());
        assertEquals(IssueCode.E_PARAM_RANGE, bad.issues().get(0).code());
        assertEquals(List.of("x/p"), bad.issues().get(0).subjects());
        SemanticPlan withNest = p.apply(SemanticPlan.empty("p"), new PlanPatch("p", 0, "t", List.of(
                new PlanOp.AddNode(new PlanNode("y", "mod:nest", null, abs(0, 0, 0, 0), Map.of(), Set.of(), ""))))).plan();
        assertEquals(IssueCode.E_UNKNOWN_PART, expander.expand(withNest, bundle, Router.NONE).issues().get(0).code());
    }

    /** A motor with a shaft below it in the template tree, so an internal connection can join the two. */
    private static List<PlanNode> motorAndShaft(String motorId, String shaftId) {
        return List.of(TestParts.at(motorId, "test:motor", null, 0, 0, 0), TestParts.at(shaftId, "test:shaft", motorId, 1, 0, 0));
    }

    private ExpandResult expandOne(ModuleTemplate template, String instanceId, Anchor anchor) {
        TemplateBundle bundle = new TemplateBundle(List.of(template));
        SemanticPlan plan = planWith(bundle, List.of(new PlanOp.AddNode(instanceOf(template.id(), instanceId, null, anchor))));
        return expander.expand(plan, bundle, Router.NONE);
    }

    @Test
    void aTemplateWithTheSameNodeIdTwiceIsRefusedAndItsInstanceIsNotExpanded() {
        ModuleTemplate twin = template("mod:twin", List.of(
                TestParts.at("x", "test:motor", null, 0, 0, 0),
                TestParts.at("x", "test:motor", null, 5, 0, 0),
                TestParts.at("y", "test:shaft", "x", 1, 0, 0),
                TestParts.at("x", "test:motor", null, 9, 0, 0),
                TestParts.at("inner", "mod:test_line", null, 3, 0, 0)), List.of(explicit("run", "x", "out", "y", "in")));
        ExpandResult r = expandOne(twin, "t1", abs(0, 0, 0, 0));
        assertNull(r.plan());
        assertEquals(List.of("E-ID-DUPLICATE:t1/x#template"), ids(r),
                "one issue for the id; the instance is skipped, so the part that would be E-UNKNOWN-PART is never looked at");
        assertEquals(List.of("t1/x"), r.issues().get(0).subjects());
    }

    @Test
    void templateNodeIdsMustBeWellFormed() {
        String tooLong = "a".repeat(49);
        ModuleTemplate bad = template("mod:bad-ids", List.of(
                TestParts.at("Motor", "test:motor", null, 0, 0, 0),
                TestParts.at("Motor", "test:motor", null, 2, 0, 0),
                TestParts.at("", "test:motor", null, 4, 0, 0),
                TestParts.at("a/b", "test:motor", null, 6, 0, 0),
                TestParts.at(tooLong, "test:motor", null, 8, 0, 0)), List.of());
        ExpandResult r = expandOne(bad, "t1", abs(0, 0, 0, 0));
        assertNull(r.plan());
        assertEquals(List.of("E-ID-INVALID:t1/Motor#template", "E-ID-INVALID:t1/#template", "E-ID-INVALID:t1/a/b#template",
                "E-ID-INVALID:t1/" + tooLong + "#template"), ids(r), "a repeated bad id is one issue, not also a duplicate");
        assertEquals(List.of("t1/Motor"), r.issues().get(0).subjects());
    }

    @Test
    void templateIdsOfTheLongestAllowedLengthAreAccepted() {
        String longest = "n".repeat(48);
        String longestConnection = "c".repeat(48);
        ModuleTemplate ok = template("mod:long-ids", motorAndShaft("motor", longest),
                List.of(explicit(longestConnection, "motor", "out", longest, "in")));
        ExpandResult r = expandOne(ok, "t1", abs(0, 0, 0, 0));
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        assertEquals("t1/" + longest, find(r.plan(), "t1/" + longest).id());
        assertEquals("t1/" + longestConnection, r.plan().routed().get(0).connectionId());
    }

    @Test
    void templateConnectionIdsMustBeWellFormedAndUnique() {
        // the extra part would be E-UNKNOWN-PART if the instance were expanded despite its bad connection ids
        List<PlanNode> nodes = new ArrayList<>(motorAndShaft("motor", "s"));
        nodes.add(TestParts.at("inner", "mod:test_line", null, 3, 0, 0));
        Connection run = explicit("run", "motor", "out", "s", "in");
        ModuleTemplate repeated = template("mod:dup-conn", nodes, List.of(run, run, explicit("run", "motor", "out", "s", "in", "s")));
        String tooLong = "c".repeat(49);
        ModuleTemplate badForm = template("mod:bad-conn", nodes, List.of(
                explicit("RUN", "motor", "out", "s", "in"), explicit("RUN", "motor", "out", "s", "in"),
                explicit(tooLong, "motor", "out", "s", "in"), run));
        TemplateBundle bundle = new TemplateBundle(List.of(repeated, badForm));
        SemanticPlan plan = planWith(bundle, List.of(
                new PlanOp.AddNode(instanceOf("mod:dup-conn", "t1", null, abs(0, 0, 0, 0))),
                new PlanOp.AddNode(instanceOf("mod:bad-conn", "t2", null, abs(10, 0, 0, 0)))));
        ExpandResult r = expander.expand(plan, bundle, Router.NONE);
        assertNull(r.plan());
        assertEquals(List.of("E-ID-DUPLICATE:t1/run#template", "E-ID-INVALID:t2/RUN#template",
                "E-ID-INVALID:t2/" + tooLong + "#template"), ids(r));
    }

    @Test
    void aNodeAndAConnectionOfATemplateMayShareAnId() {
        ModuleTemplate shared = template("mod:shared-name", motorAndShaft("motor", "run"),
                List.of(explicit("run", "motor", "out", "run", "in")));
        ExpandResult r = expandOne(shared, "t1", abs(0, 0, 0, 0));
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        assertEquals("t1/run", find(r.plan(), "t1/run").id());
        assertEquals("t1/run", r.plan().routed().get(0).connectionId());
    }

    @Test
    void templateIdProblemsAreReportedWithNodesBeforeConnectionsAndBesideThePlacementProblem() {
        ModuleTemplate messy = template("mod:messy", List.of(
                TestParts.at("x", "test:motor", null, 0, 0, 0), TestParts.at("x", "test:motor", null, 2, 0, 0)),
                List.of(explicit("RUN", "x", "out", "x", "out")));
        ExpandResult r = expandOne(messy, "t1", new Anchor.InSlot("slot-a", Rot.NONE));
        assertNull(r.plan());
        assertEquals(List.of("E-ID-DUPLICATE:t1/x#template", "E-ID-INVALID:t1/RUN#template", "E-ANCHOR:t1#slot"), ids(r));
    }

    @Test
    void aNodeInsideAModuleInstanceAndSlotPartsAreHandledExplicitly() {
        SemanticPlan inside = plan(List.of(new PlanOp.AddNode(module("line-1", null, abs(0, 0, 0, 0))),
                new PlanOp.AddNode(TestParts.at("extra", "test:motor", "line-1", 1, 0, 0))));
        ExpandResult r = expander.expand(inside, TestParts.bundle(), Router.NONE);
        assertEquals(IssueCode.E_ANCHOR, r.issues().get(0).code());
        assertEquals(List.of("E-ANCHOR:extra#parent"), ids(r));
        // a module nested in another instance is refused as one node, not by the loose ends its parts would leave
        SemanticPlan nested = plan(List.of(new PlanOp.AddNode(module("outer", null, abs(0, 0, 0, 0))),
                new PlanOp.AddNode(module("inner", "outer", abs(0, 0, 5, 0)))));
        assertEquals(List.of("E-ANCHOR:inner#parent"), ids(expander.expand(nested, TestParts.bundle(), Router.NONE)));

        PlanNode slotPart = new PlanNode("m", "test:motor", null, new Anchor.InSlot("slot-a", new Rot(1, false)), Map.of(), Set.of(), "");
        SemanticPlan slotPlan = plan(List.of(new PlanOp.AddNode(slotPart)));
        ExpandedPlan e = slotted.expand(slotPlan, TemplateBundle.EMPTY, Router.NONE).plan();
        assertEquals(new Anchor.Absolute(new LocalPos(5, 0, 5), new Rot(1, false)), e.primitiveNodes().get(0).anchor(),
                "the compiler never sees an InSlot anchor");
        assertEquals(IssueCode.E_ANCHOR, expander.expand(slotPlan, TemplateBundle.EMPTY, Router.NONE).issues().get(0).code());

        PlanNode slotChild = new PlanNode("c", "test:motor", "room", new Anchor.InSlot("slot-a", Rot.NONE), Map.of(), Set.of(), "");
        SemanticPlan withParent = plan(List.of(new PlanOp.AddNode(TestParts.at("room", "micra:structure", null, 0, 0, 0)),
                new PlanOp.AddNode(slotChild)));
        assertEquals(IssueCode.E_ANCHOR, slotted.expand(withParent, TemplateBundle.EMPTY, Router.NONE).issues().get(0).code());
    }

    @Test
    void aHandBuiltPlanWithParentLoopsOrMissingParentsIsReportedNotFollowed() {
        SemanticPlan plan = handBuilt(List.of(
                TestParts.at("a", "micra:pillar", "b", 0, 0, 0),
                TestParts.at("b", "micra:pillar", "a", 0, 0, 0),
                TestParts.at("c", "micra:pillar", "ghost", 0, 0, 0),
                TestParts.at("d", "micra:pillar", null, 0, 0, 0)), List.of());
        ExpandResult r = assertTimeoutPreemptively(WALK_TIMEOUT, () -> expander.expand(plan, TemplateBundle.EMPTY, Router.NONE));
        assertNull(r.plan());
        assertEquals(List.of("E-ANCHOR:a#cycle", "E-ANCHOR:c#parent"), ids(r));
    }

    @Test
    void originsResolveThroughParents() {
        List<PlanNode> nodes = new ArrayList<>(List.of(
                TestParts.at("a", "micra:structure", null, 2, 0, 3),
                TestParts.at("b", "micra:pillar", "a", 1, 0, 1)));
        List<Issue> issues = new ArrayList<>();
        Map<String, LocalPos> o = Origins.resolve(nodes, SlotResolver.NONE, issues);
        assertEquals(new LocalPos(2, 0, 3), o.get("a"));
        assertEquals(new LocalPos(3, 0, 4), o.get("b"));
        assertTrue(issues.isEmpty());
    }

    @Test
    void theExpandedListsFollowThePlanOrderWithEachInstanceSplicedInPlace() {
        SemanticPlan plan = plan(List.of(
                new PlanOp.AddNode(TestParts.at("m", "test:motor", null, 0, 0, 0)),
                new PlanOp.AddNode(module("line-2", null, abs(10, 0, 0, 0))),
                new PlanOp.AddNode(TestParts.at("p", "test:press", null, 3, 0, 0)),
                new PlanOp.AddNode(module("line-1", null, abs(20, 0, 0, 0))),
                new PlanOp.AddConnection(explicit("c-2", "m", "out", "p", "power_in")),
                new PlanOp.AddConnection(explicit("c-1", "m", "out", "p", "power_in"))));
        ExpandedPlan e = expander.expand(plan, TestParts.bundle(), Router.NONE).plan();
        assertEquals(List.of("m", "line-2/motor", "line-2/shaft", "p", "line-1/motor", "line-1/shaft"),
                e.primitiveNodes().stream().map(PlanNode::id).toList());
        assertEquals(List.of("c-2", "c-1", "line-2/link", "line-1/link"),
                e.routed().stream().map(RoutedConnection::connectionId).toList());
    }

    @Test
    void theResultRecordsCopyTheListsTheyAreGiven() {
        List<LocalPos> path = new ArrayList<>(List.of(new LocalPos(1, 0, 0)));
        List<PlanNode> middle = new ArrayList<>();
        RoutedConnection routed = new RoutedConnection("c", middle, path);
        path.add(new LocalPos(2, 0, 0));
        middle.add(TestParts.at("x", "test:shaft", null, 0, 0, 0));
        assertEquals(1, routed.path().size());
        assertTrue(routed.intermediateNodes().isEmpty());

        List<PlanNode> nodes = new ArrayList<>();
        List<RoutedConnection> routedList = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        ExpandedPlan e = new ExpandedPlan(SemanticPlan.empty("p"), nodes, routedList, hashes);
        nodes.add(TestParts.at("x", "test:shaft", null, 0, 0, 0));
        routedList.add(routed);
        hashes.add("h");
        assertTrue(e.primitiveNodes().isEmpty());
        assertTrue(e.routed().isEmpty());
        assertTrue(e.templateHashes().isEmpty());

        List<Issue> issues = new ArrayList<>();
        ExpandResult r = new ExpandResult(null, issues);
        issues.add(Issue.of(IssueCode.E_ANCHOR, List.of("x"), "m"));
        assertTrue(r.issues().isEmpty());
    }
}
