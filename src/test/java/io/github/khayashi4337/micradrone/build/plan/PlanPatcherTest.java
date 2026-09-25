package io.github.khayashi4337.micradrone.build.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.TestParts;
import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.ConnKind;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Constraints;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.LogisticsPlan;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.ParamValue.EnumV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.IntV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.MaterialV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.NumV;
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
import io.github.khayashi4337.micradrone.build.model.Site;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanPatcherTest {
    private final PlanPatcher patcher = new PlanPatcher(TestParts.registry(), TestParts.bundle());

    private static PlanNode node(String id, String type, String parent, Map<String, ParamValue> params) {
        return new PlanNode(id, type, parent, new Anchor.Absolute(new LocalPos(0, 0, 0), Rot.NONE), params, Set.of(), "");
    }

    private static PlanPatch patch(int base, PlanOp... ops) {
        return new PlanPatch("p-" + base, base, "test", List.of(ops));
    }

    private static SemanticPlan hut(PlanPatcher patcher) {
        PatchResult r = patcher.apply(SemanticPlan.empty("plan"), patch(0,
                new PlanOp.AddNode(node("hut", "micra:structure", null, Map.of("width", new IntV(7)))),
                new PlanOp.AddNode(node("wall-n", "micra:wall", "hut", Map.of("side", new StrV("north"))))));
        assertTrue(r.ok(), r.issues().toString());
        return r.plan();
    }

    private static List<String> codes(PatchResult r) {
        return r.issues().stream().map(i -> i.code().label()).toList();
    }

    @Test
    void addingNodesTypesParametersAndBumpsTheRevision() {
        SemanticPlan plan = hut(patcher);
        assertEquals(1, plan.revision());
        assertEquals(0, plan.parentRevision());
        assertEquals(new IntV(7), plan.node("hut").orElseThrow().params().get("width"));
        assertEquals(new EnumV("north"), plan.node("wall-n").orElseThrow().params().get("side"));
        assertEquals(1, plan.node("hut").orElseThrow().params().size(), "defaults are not stored");
    }

    @Test
    void aStalePatchIsRefusedWhole() {
        SemanticPlan plan = hut(patcher);
        PatchResult r = patcher.apply(plan, patch(0, new PlanOp.RemoveNode("wall-n")));
        assertFalse(r.ok());
        assertEquals(List.of("E-PATCH-STALE"), codes(r));
    }

    @Test
    void anErrorRejectsThePatchAndLeavesTheOriginalUntouched() {
        SemanticPlan plan = hut(patcher);
        PatchResult r = patcher.apply(plan, patch(1,
                new PlanOp.AddNode(node("wall-s", "micra:wall", "hut", Map.of("side", new StrV("south")))),
                new PlanOp.AddNode(node("bad", "micra:wall", "hut", Map.of("side", new StrV("up"))))));
        assertNull(r.plan());
        assertEquals(2, plan.nodes().size());
        assertEquals(1, plan.revision());
    }

    @Test
    void idsAreValidatedAndMustBeUnique() {
        SemanticPlan plan = hut(patcher);
        PatchResult bad = patcher.apply(plan, patch(1, new PlanOp.AddNode(node("Bad_Id", "micra:pillar", null, Map.of()))));
        assertEquals(List.of("E-ID-INVALID"), codes(bad));
        PatchResult tooLong = patcher.apply(plan, patch(1, new PlanOp.AddNode(node("a".repeat(49), "micra:pillar", null, Map.of()))));
        assertEquals(List.of("E-ID-INVALID"), codes(tooLong));
        PatchResult dup = patcher.apply(plan, patch(1, new PlanOp.AddNode(node("hut", "micra:pillar", null, Map.of()))));
        assertEquals(List.of("E-ID-DUPLICATE"), codes(dup));
    }

    @Test
    void unknownPartsGetNearbySuggestions() {
        PatchResult r = patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.AddNode(node("x", "micra:walll", null, Map.of()))));
        Issue issue = r.issues().get(0);
        assertEquals(IssueCode.E_UNKNOWN_PART, issue.code());
        assertTrue(issue.hints().stream().anyMatch(h -> h.kind().equals("USE_PART") && h.args().get("ids").contains("micra:wall")),
                issue.hints().toString());
    }

    @Test
    void parameterProblemsAreParamRangeIssues() {
        PatchResult r = patcher.apply(SemanticPlan.empty("p"), patch(0,
                new PlanOp.AddNode(node("hut", "micra:structure", null, Map.of("width", new IntV(2), "nope", new IntV(1)))),
                new PlanOp.AddNode(node("hut-2", "micra:structure", null, Map.of("width", new NumV(3_000_000_000.0))))));
        assertEquals(3, r.issues().size(), r.issues().toString());
        assertTrue(r.issues().stream().allMatch(i -> i.code() == IssueCode.E_PARAM_RANGE));
    }

    @Test
    void parentAndAnchorReferencesAreChecked() {
        PatchResult noParent = patcher.apply(SemanticPlan.empty("p"),
                patch(0, new PlanOp.AddNode(node("wall-n", "micra:wall", "ghost", Map.of("side", new StrV("north"))))));
        assertEquals(List.of("E-ANCHOR"), codes(noParent));

        SemanticPlan plan = hut(patcher);
        PlanNode onMissing = new PlanNode("d", "micra:door", "hut", new Anchor.OnSurface("ghost", Side.OUTER, 1, 0), Map.of(), Set.of(), "");
        assertEquals(List.of("E-ANCHOR"), codes(patcher.apply(plan, patch(1, new PlanOp.AddNode(onMissing)))));
        PlanNode onNonWall = new PlanNode("d", "micra:door", "hut", new Anchor.OnSurface("hut", Side.OUTER, 1, 0), Map.of(), Set.of(), "");
        assertEquals(List.of("E-ANCHOR"), codes(patcher.apply(plan, patch(1, new PlanOp.AddNode(onNonWall)))));
        PlanNode badSide = new PlanNode("d", "micra:door", "hut", new Anchor.OnSurface("wall-n", Side.TOP, 1, 0), Map.of(), Set.of(), "");
        assertEquals(List.of("E-ANCHOR"), codes(patcher.apply(plan, patch(1, new PlanOp.AddNode(badSide)))));
        PlanNode negative = new PlanNode("d", "micra:door", "hut", new Anchor.OnSurface("wall-n", Side.OUTER, -1, 0), Map.of(), Set.of(), "");
        assertEquals(List.of("E-ANCHOR"), codes(patcher.apply(plan, patch(1, new PlanOp.AddNode(negative)))));
        PlanNode good = new PlanNode("d", "micra:door", "hut", new Anchor.OnSurface("wall-n", Side.OUTER, 3, 0), Map.of(), Set.of(), "");
        assertTrue(patcher.apply(plan, patch(1, new PlanOp.AddNode(good))).ok());
    }

    @Test
    void updateParamsMergesAndValidatesTheWholeThing() {
        SemanticPlan plan = hut(patcher);
        PatchResult ok = patcher.apply(plan, patch(1, new PlanOp.UpdateParams("hut", Map.of("depth", new NumV(9.0)))));
        assertTrue(ok.ok(), ok.issues().toString());
        PlanNode updated = ok.plan().node("hut").orElseThrow();
        assertEquals(new IntV(7), updated.params().get("width"));
        assertEquals(new IntV(9), updated.params().get("depth"));

        assertEquals(List.of("E-PARAM-RANGE"), codes(patcher.apply(plan, patch(1, new PlanOp.UpdateParams("hut", Map.of("width", new IntV(99)))))));
        assertEquals(List.of("E-ANCHOR"), codes(patcher.apply(plan, patch(1, new PlanOp.UpdateParams("ghost", Map.of())))));
    }

    @Test
    void hugePositionsAndControlCharactersAreRefused() {
        PlanNode far = new PlanNode("a", "micra:pillar", null, new Anchor.Absolute(new LocalPos(Integer.MAX_VALUE, 0, 0), Rot.NONE), Map.of(), Set.of(), "");
        assertEquals(List.of("E-PARAM-RANGE"), codes(patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.AddNode(far)))));
        SemanticPlan plan = hut(patcher);
        PlanNode wideDoor = new PlanNode("d", "micra:door", "hut", new Anchor.OnSurface("wall-n", Side.OUTER, 40_000_000, 0), Map.of(), Set.of(), "");
        assertEquals(List.of("E-ANCHOR"), codes(patcher.apply(plan, patch(1, new PlanOp.AddNode(wideDoor)))));
        Site farSite = new Site("minecraft:overworld", new BuildFrame(new IntPos(Integer.MAX_VALUE, 0, 0), Facing.NORTH), new Box(0, 0, 0, 5, 5, 5), "", "");
        assertEquals(List.of("E-PARAM-RANGE"), codes(patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.SetSite(farSite)))));
        PlanNode crLabel = new PlanNode("l", "micra:pillar", null, abs(0, 0, 0), Map.of(), Set.of(), "line\r\nbreak");
        assertEquals(List.of("E-PARAM-RANGE"), codes(patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.AddNode(crLabel)))));
        PlanNode crTag = new PlanNode("l", "micra:pillar", null, abs(0, 0, 0), Map.of(), Set.of("bad\rtag"), "");
        assertEquals(List.of("E-PARAM-RANGE"), codes(patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.AddNode(crTag)))));
        PlanNode fine = new PlanNode("l", "micra:pillar", null, abs(0, 0, 0), Map.of(), Set.of("tab\tok"), "two\nlines");
        assertTrue(patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.AddNode(fine))).ok(), "newline and tab are allowed");
    }

    private static Anchor abs(int u, int v, int w) {
        return new Anchor.Absolute(new LocalPos(u, v, w), Rot.NONE);
    }

    @Test
    void moveNodeChecksTheNewAnchor() {
        SemanticPlan plan = hut(patcher);
        PatchResult ok = patcher.apply(plan, patch(1, new PlanOp.MoveNode("wall-n", new Anchor.Absolute(new LocalPos(1, 0, 1), Rot.NONE))));
        assertTrue(ok.ok());
        assertEquals(new Anchor.Absolute(new LocalPos(1, 0, 1), Rot.NONE), ok.plan().node("wall-n").orElseThrow().anchor());
        assertEquals(List.of("E-ANCHOR"), codes(patcher.apply(plan, patch(1, new PlanOp.MoveNode("ghost", new Anchor.Absolute(new LocalPos(0, 0, 0), Rot.NONE))))));
        assertEquals(List.of("E-ANCHOR"), codes(patcher.apply(plan, patch(1, new PlanOp.MoveNode("wall-n", new Anchor.OnSurface("wall-n", Side.OUTER, 0, 0))))));
    }

    @Test
    void removeNodeRefusesWhileDependentsRemain() {
        SemanticPlan plan = hut(patcher);
        PatchResult withChild = patcher.apply(plan, patch(1, new PlanOp.RemoveNode("hut")));
        assertEquals(List.of("E-ANCHOR"), codes(withChild));
        assertTrue(withChild.issues().get(0).data().get("dependents").contains("wall-n"));
        assertTrue(withChild.issues().get(0).hints().stream().anyMatch(h -> h.kind().equals("REMOVE_FIRST")));

        PatchResult leaf = patcher.apply(plan, patch(1, new PlanOp.RemoveNode("wall-n")));
        assertTrue(leaf.ok());
        assertEquals(1, leaf.plan().nodes().size());

        // removing the child and then the parent in one patch is fine
        PatchResult both = patcher.apply(plan, patch(1, new PlanOp.RemoveNode("wall-n"), new PlanOp.RemoveNode("hut")));
        assertTrue(both.ok(), both.issues().toString());
        assertEquals(0, both.plan().nodes().size());
    }

    @Test
    void connectionsNeedRealNodesAndPorts() {
        PatchResult base = patcher.apply(SemanticPlan.empty("p"), patch(0,
                new PlanOp.AddNode(node("m", "test:motor", null, Map.of())),
                new PlanOp.AddNode(node("s", "test:shaft", null, Map.of())),
                new PlanOp.AddNode(node("hut", "micra:structure", null, Map.of()))));
        assertTrue(base.ok(), base.issues().toString());
        SemanticPlan plan = base.plan();
        Connection good = new Connection("c-1", new PortRef("m", "out"), new PortRef("s", "in"), ConnKind.ROTATION, Routing.AUTO, Constraints.NONE);
        PatchResult ok = patcher.apply(plan, patch(1, new PlanOp.AddConnection(good)));
        assertTrue(ok.ok(), ok.issues().toString());

        Connection noPort = new Connection("c-2", new PortRef("m", "nope"), new PortRef("s", "in"), ConnKind.ROTATION, Routing.AUTO, Constraints.NONE);
        Connection noNode = new Connection("c-3", new PortRef("ghost", "out"), new PortRef("s", "in"), ConnKind.ROTATION, Routing.AUTO, Constraints.NONE);
        Connection noVia = new Connection("c-4", new PortRef("m", "out"), new PortRef("s", "in"), ConnKind.ROTATION, new Routing.Explicit(List.of("ghost")), Constraints.NONE);
        Connection buildingPart = new Connection("c-5", new PortRef("hut", "out"), new PortRef("s", "in"), ConnKind.ROTATION, Routing.AUTO, Constraints.NONE);
        for (Connection bad : List.of(noPort, noNode, noVia, buildingPart)) {
            assertEquals(List.of("E-CONN-INVALID"), codes(patcher.apply(plan, patch(1, new PlanOp.AddConnection(bad)))), bad.id());
        }
        assertEquals(List.of("E-ID-DUPLICATE"), codes(patcher.apply(ok.plan(), patch(2, new PlanOp.AddConnection(good)))));
        assertEquals(List.of("E-CONN-INVALID"), codes(patcher.apply(ok.plan(), patch(2, new PlanOp.RemoveConnection("ghost")))));
        assertTrue(patcher.apply(ok.plan(), patch(2, new PlanOp.RemoveConnection("c-1"))).ok());
    }

    @Test
    void removingANodeThatAConnectionUsesIsRefused() {
        PatchResult base = patcher.apply(SemanticPlan.empty("p"), patch(0,
                new PlanOp.AddNode(node("m", "test:motor", null, Map.of())),
                new PlanOp.AddNode(node("s", "test:shaft", null, Map.of())),
                new PlanOp.AddConnection(new Connection("c-1", new PortRef("m", "out"), new PortRef("s", "in"),
                        ConnKind.ROTATION, Routing.AUTO, Constraints.NONE))));
        assertTrue(base.ok(), base.issues().toString());
        assertEquals(List.of("E-ANCHOR"), codes(patcher.apply(base.plan(), patch(1, new PlanOp.RemoveNode("m")))));
    }

    @Test
    void moduleInstancesNeedAKnownTemplateAndTakeNoParameters() {
        assertTrue(patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.AddNode(node("line-1", "mod:test_line", null, Map.of())))).ok());
        assertEquals(List.of("E-UNKNOWN-PART"), codes(patcher.apply(SemanticPlan.empty("p"),
                patch(0, new PlanOp.AddNode(node("x", "mod:ghost", null, Map.of()))))));
        assertEquals(List.of("E-PARAM-RANGE"), codes(patcher.apply(SemanticPlan.empty("p"),
                patch(0, new PlanOp.AddNode(node("x", "mod:test_line", null, Map.of("n", new IntV(1))))))));
        // the module's own port can be connected
        PatchResult withModule = patcher.apply(SemanticPlan.empty("p"), patch(0,
                new PlanOp.AddNode(node("line-1", "mod:test_line", null, Map.of())),
                new PlanOp.AddNode(node("s", "test:shaft", null, Map.of())),
                new PlanOp.AddConnection(new Connection("c-1", new PortRef("line-1", "out"), new PortRef("s", "in"),
                        ConnKind.ROTATION, Routing.AUTO, Constraints.NONE))));
        assertTrue(withModule.ok(), withModule.issues().toString());
    }

    @Test
    void styleSiteAndLogisticsAreValidated() {
        StyleSpec badRole = new StyleSpec(Map.of("Roof!", "minecraft:stone"), Set.of());
        StyleSpec badBlock = new StyleSpec(Map.of("roof", "stone"), Set.of());
        assertEquals(List.of("E-PARAM-RANGE"), codes(patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.SetStyle(badRole)))));
        assertEquals(List.of("E-PARAM-RANGE"), codes(patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.SetStyle(badBlock)))));
        assertTrue(patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.SetStyle(new StyleSpec(Map.of("roof", "minecraft:bricks"), Set.of("cozy"))))).ok());

        Site badDim = new Site("overworld", new BuildFrame(new IntPos(0, 0, 0), Facing.NORTH), new Box(0, 0, 0, 5, 5, 5), "", "");
        assertEquals(List.of("E-PARAM-RANGE"), codes(patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.SetSite(badDim)))));
        Site good = new Site("minecraft:overworld", new BuildFrame(new IntPos(0, 0, 0), Facing.NORTH), new Box(0, 0, 0, 5, 5, 5), "", "");
        PatchResult withSite = patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.SetSite(good)));
        assertTrue(withSite.ok());
        assertSame(good, withSite.plan().site());

        LogisticsPlan dangling = new LogisticsPlan(List.of(),
                List.of(new LogisticsPlan.Route("r-1", "ghost", "ghost", List.of(), null)), List.of());
        assertEquals(List.of("E-CONN-INVALID"), codes(patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.SetLogistics(dangling)))));
        LogisticsPlan dupDocks = new LogisticsPlan(List.of(
                new LogisticsPlan.Dock("d", new Box(0, 0, 0, 1, 0, 1), new Box(0, 1, 0, 1, 4, 1), Facing.NORTH, List.of(), List.of()),
                new LogisticsPlan.Dock("d", new Box(0, 0, 0, 1, 0, 1), new Box(0, 1, 0, 1, 4, 1), Facing.NORTH, List.of(), List.of())),
                List.of(), List.of());
        assertEquals(List.of("E-ID-DUPLICATE"), codes(patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.SetLogistics(dupDocks)))));
        SemanticPlan withLogistics = patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.SetLogistics(
                new LogisticsPlan(List.of(), List.of(), List.of())))).plan();
        assertNotNull(withLogistics.logistics());
        assertNull(patcher.apply(withLogistics, patch(1, new PlanOp.SetLogistics(null))).plan().logistics());
    }

    @Test
    void opsInOnePatchApplyInOrder() {
        // add -> update -> remove -> add again with the same id, all in one patch
        PatchResult r = patcher.apply(SemanticPlan.empty("p"), patch(0,
                new PlanOp.AddNode(node("a", "micra:pillar", null, Map.of("height", new IntV(3)))),
                new PlanOp.UpdateParams("a", Map.of("height", new IntV(5))),
                new PlanOp.RemoveNode("a"),
                new PlanOp.AddNode(node("a", "micra:pillar", null, Map.of("height", new IntV(9))))));
        assertTrue(r.ok(), r.issues().toString());
        assertEquals(new IntV(9), r.plan().node("a").orElseThrow().params().get("height"));
        // a child added before its parent is a reference to something that does not exist yet
        PatchResult early = patcher.apply(SemanticPlan.empty("p"), patch(0,
                new PlanOp.AddNode(node("wall-n", "micra:wall", "hut", Map.of("side", new StrV("north")))),
                new PlanOp.AddNode(node("hut", "micra:structure", null, Map.of()))));
        assertEquals(List.of("E-ANCHOR"), codes(early));
    }

    @Test
    void normalizeFixesTheTypesOfALooselyReadPlan() {
        PlanNode loose = node("hut", "micra:structure", null, Map.of("width", new NumV(9.0)));
        PlanNode wall = node("wall-n", "micra:wall", "hut", Map.of("side", new StrV("north"), "material", new StrV("roof")));
        SemanticPlan plan = new SemanticPlan(1, "p", 4, 3, null, StyleSpec.EMPTY, List.of(loose, wall), List.of(), null,
                Provenance.NONE);
        PatchResult r = patcher.normalize(plan);
        assertTrue(r.ok(), r.issues().toString());
        assertEquals(new IntV(9), r.plan().node("hut").orElseThrow().params().get("width"));
        assertEquals(new EnumV("north"), r.plan().node("wall-n").orElseThrow().params().get("side"));
        assertEquals(new MaterialV("roof"), r.plan().node("wall-n").orElseThrow().params().get("material"));
        assertEquals(4, r.plan().revision(), "normalize keeps the revision and the meta fields");
        assertEquals(plan.planId(), r.plan().planId());
    }

    @Test
    void theSameDesignEnteredTwoWaysHasTheSameContentHash() {
        PatchResult a = patcher.apply(SemanticPlan.empty("p"), patch(0,
                new PlanOp.AddNode(node("hut", "micra:structure", null, Map.of("width", new NumV(9.0))))));
        PatchResult b = patcher.apply(SemanticPlan.empty("q"), patch(0,
                new PlanOp.AddNode(node("hut", "micra:structure", null, Map.of("width", new IntV(9))))));
        assertEquals(a.plan().contentHash(), b.plan().contentHash());
    }
}
