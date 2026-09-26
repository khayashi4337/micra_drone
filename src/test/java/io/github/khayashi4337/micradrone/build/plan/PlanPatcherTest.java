package io.github.khayashi4337.micradrone.build.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.TestParts;
import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.ConnKind;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Constraints;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.FixHint;
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
import io.github.khayashi4337.micradrone.build.model.PlanJson;
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
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.time.Duration;
import java.util.ArrayList;
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

    private static void assertPositiveZero(ParamValue v) {
        double d = ((NumV) v).value();
        assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(d), "the value is +0.0, not -0.0: " + d);
    }

    @Test
    void aNegativeZeroNumberParameterIsStoredAsPositiveZero() {
        // -0.0 cannot survive a trip through decimal text, so the plan never keeps it (a plan must equal its replay)
        PlanPatcher dialPatcher = new PlanPatcher(TestParts.registryWithDial(), TestParts.bundle());
        PlanNode dial = node("dial", "test:dial", null, Map.of("speed", new NumV(-0.0)));
        PatchResult added = dialPatcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.AddNode(dial)));
        assertTrue(added.ok(), added.issues().toString());
        assertPositiveZero(added.plan().node("dial").orElseThrow().params().get("speed"));

        PlanNode running = node("dial", "test:dial", null, Map.of("speed", new NumV(12.5)));
        SemanticPlan plan = dialPatcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.AddNode(running))).plan();
        PatchResult updated = dialPatcher.apply(plan, patch(1, new PlanOp.UpdateParams("dial", Map.of("speed", new NumV(-0.0)))));
        assertTrue(updated.ok(), updated.issues().toString());
        assertPositiveZero(updated.plan().node("dial").orElseThrow().params().get("speed"));
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

    // ------------------------------------------------------------------ surface cycles

    private static PlanNode wall(String id, Anchor anchor) {
        return new PlanNode(id, "micra:wall", "hut", anchor, Map.of("side", new StrV("north")), Set.of(), "");
    }

    private static Anchor onWall(String wallId) {
        return new Anchor.OnSurface(wallId, Side.OUTER, 0, 0);
    }

    /** A hut with the given walls, all placed absolutely, at revision 1. */
    private SemanticPlan hutWithWalls(String... wallIds) {
        List<PlanOp> ops = new ArrayList<>();
        ops.add(new PlanOp.AddNode(node("hut", "micra:structure", null, Map.of())));
        for (String id : wallIds) {
            ops.add(new PlanOp.AddNode(wall(id, abs(0, 0, 0))));
        }
        PatchResult r = patcher.apply(SemanticPlan.empty("plan"), new PlanPatch("p-0", 0, "test", ops));
        assertTrue(r.ok(), r.issues().toString());
        return r.plan();
    }

    private static void assertSingleIssue(PatchResult r, IssueCode code, String issueId, List<String> subjects) {
        assertNull(r.plan(), "the patch is refused whole");
        assertEquals(1, r.issues().size(), r.issues().toString());
        Issue issue = r.issues().get(0);
        assertEquals(code, issue.code());
        assertEquals(issueId, issue.id());
        assertEquals(subjects, issue.subjects());
    }

    @Test
    void movingANodeOntoASurfaceThatRestsOnItIsACycle() {
        SemanticPlan plan = hutWithWalls("w1", "w2");
        PatchResult first = patcher.apply(plan, patch(1, new PlanOp.MoveNode("w1", onWall("w2"))));
        assertTrue(first.ok(), first.issues().toString());

        // w1 rests on w2, so w2 cannot rest on w1
        assertSingleIssue(patcher.apply(first.plan(), patch(2, new PlanOp.MoveNode("w2", onWall("w1")))),
                IssueCode.E_ANCHOR, "E-ANCHOR:w2#cycle", List.of("w2"));
        // the same inside one patch: the second operation sees the first one's result
        assertSingleIssue(patcher.apply(plan, patch(1,
                        new PlanOp.MoveNode("w1", onWall("w2")), new PlanOp.MoveNode("w2", onWall("w1")))),
                IssueCode.E_ANCHOR, "E-ANCHOR:w2#cycle", List.of("w2"));
    }

    @Test
    void aLoopOfThreeSurfacesIsRefused() {
        SemanticPlan plan = hutWithWalls("w1", "w2", "w3");
        PatchResult twoLinks = patcher.apply(plan, patch(1,
                new PlanOp.MoveNode("w1", onWall("w2")), new PlanOp.MoveNode("w2", onWall("w3"))));
        assertTrue(twoLinks.ok(), twoLinks.issues().toString());

        assertSingleIssue(patcher.apply(plan, patch(1,
                        new PlanOp.MoveNode("w1", onWall("w2")), new PlanOp.MoveNode("w2", onWall("w3")),
                        new PlanOp.MoveNode("w3", onWall("w1")))),
                IssueCode.E_ANCHOR, "E-ANCHOR:w3#cycle", List.of("w3"));
        assertSingleIssue(patcher.apply(twoLinks.plan(), patch(2, new PlanOp.MoveNode("w3", onWall("w1")))),
                IssueCode.E_ANCHOR, "E-ANCHOR:w3#cycle", List.of("w3"));
    }

    @Test
    void aLongChainOfSurfacesWithoutALoopIsAccepted() {
        SemanticPlan plan = hutWithWalls("w1", "w2", "w3", "w4", "w5", "w6");
        PatchResult chain = patcher.apply(plan, patch(1,
                new PlanOp.MoveNode("w2", onWall("w1")), new PlanOp.MoveNode("w3", onWall("w2")),
                new PlanOp.MoveNode("w4", onWall("w3")), new PlanOp.MoveNode("w5", onWall("w4")),
                new PlanOp.MoveNode("w6", onWall("w5"))));
        assertTrue(chain.ok(), chain.issues().toString());
        assertEquals(onWall("w5"), chain.plan().node("w6").orElseThrow().anchor());

        // the head of the chain cannot rest on its far end, however long the chain is
        assertSingleIssue(patcher.apply(chain.plan(), patch(2, new PlanOp.MoveNode("w1", onWall("w6")))),
                IssueCode.E_ANCHOR, "E-ANCHOR:w1#cycle", List.of("w1"));
        // resting on a wall in the middle of the chain is not a loop
        assertTrue(patcher.apply(chain.plan(), patch(2, new PlanOp.MoveNode("w6", onWall("w3")))).ok());
    }

    @Test
    void aLoopThatWasAlreadyThereDoesNotSpinThePatcher() {
        SemanticPlan looped = new SemanticPlan(1, "p", 1, 0, null, StyleSpec.EMPTY,
                List.of(node("hut", "micra:structure", null, Map.of()), wall("w1", onWall("w2")),
                        wall("w2", onWall("w1")), wall("w3", abs(0, 0, 0))),
                List.of(), null, Provenance.NONE);
        // w1 and w2 already rest on each other; w3 joins them without being part of that loop
        PatchResult r = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> patcher.apply(looped, patch(1, new PlanOp.MoveNode("w3", onWall("w1")))));
        assertTrue(r.ok(), r.issues().toString());
    }

    // ------------------------------------------------------------------ loops through parents AND surfaces

    /** How long the chains of the timing tests are: far deeper than a recursive walk could go on a test thread's stack. */
    private static final int LONG_CHAIN = 100_000;

    private static PlanNode chainWall(String id, String parent, Anchor anchor) {
        return new PlanNode(id, "micra:wall", parent, anchor, Map.of("side", new StrV("north")), Set.of(), "");
    }

    private static PlanNode chainDoor(String id, String parent, Anchor anchor) {
        return new PlanNode(id, "micra:door", parent, anchor, Map.of(), Set.of(), "");
    }

    /** The nodes added in the given order to an empty plan: revision 1. */
    private SemanticPlan planOfNodes(PlanNode... nodes) {
        List<PlanOp> ops = new ArrayList<>();
        for (PlanNode n : nodes) {
            ops.add(new PlanOp.AddNode(n));
        }
        PatchResult r = patcher.apply(SemanticPlan.empty("plan"), new PlanPatch("p-0", 0, "test", ops));
        assertTrue(r.ok(), r.issues().toString());
        return r.plan();
    }

    private static List<String> nodeIds(SemanticPlan plan) {
        return plan.nodes().stream().map(PlanNode::id).toList();
    }

    @Test
    void aNodeMovedOntoAWallThatWasAddedLaterIsNotALoop() {
        // door-1 is stored before wall-1 and then rests on it: nothing points back, so the patcher accepts it
        SemanticPlan plan = planOfNodes(chainDoor("door-1", null, abs(0, 0, 0)), chainWall("wall-1", null, abs(0, 0, 0)));
        Anchor onWall1 = new Anchor.OnSurface("wall-1", Side.OUTER, 1, 0);
        PatchResult r = patcher.apply(plan, patch(1, new PlanOp.MoveNode("door-1", onWall1)));
        assertTrue(r.ok(), r.issues().toString());
        assertEquals(onWall1, r.plan().node("door-1").orElseThrow().anchor());
        assertEquals(List.of("door-1", "wall-1"), nodeIds(r.plan()), "a move never reorders the stored nodes");
    }

    @Test
    void aChildThatAlsoRestsOnItsParentWallIsNotALoop() {
        // the same node is both the parent and the surface target: one dependency reached twice, not a loop
        SemanticPlan plan = planOfNodes(chainWall("wall-1", null, abs(0, 0, 0)), chainDoor("door-1", "wall-1", abs(0, 0, 0)));
        PatchResult r = patcher.apply(plan, patch(1,
                new PlanOp.MoveNode("door-1", new Anchor.OnSurface("wall-1", Side.OUTER, 2, 0))));
        assertTrue(r.ok(), r.issues().toString());
    }

    @Test
    void aWallWhoseParentIsADoorRefusesThatDoorMovedOntoIt() {
        // wall-1 hangs below door-1 (parent), so door-1 cannot rest on wall-1 (surface): each needs the other first
        SemanticPlan plan = planOfNodes(chainDoor("door-1", null, abs(0, 0, 0)), chainWall("wall-1", "door-1", abs(0, 0, 0)));
        Anchor onWall1 = new Anchor.OnSurface("wall-1", Side.OUTER, 1, 0);
        PatchResult r = patcher.apply(plan, patch(1, new PlanOp.MoveNode("door-1", onWall1)));
        assertSingleIssue(r, IssueCode.E_ANCHOR, "E-ANCHOR:door-1#cycle", List.of("door-1"));
        assertTrue(r.issues().get(0).message().contains("親子") && r.issues().get(0).message().contains("面"),
                "the message names both kinds of relation: " + r.issues().get(0).message());
        // the same inside one patch: the wall is added under the door, then the door is moved onto it
        PatchResult sameFlow = patcher.apply(SemanticPlan.empty("plan"), patch(0,
                new PlanOp.AddNode(chainDoor("door-1", null, abs(0, 0, 0))),
                new PlanOp.AddNode(chainWall("wall-1", "door-1", abs(0, 0, 0))),
                new PlanOp.MoveNode("door-1", onWall1)));
        assertSingleIssue(sameFlow, IssueCode.E_ANCHOR, "E-ANCHOR:door-1#cycle", List.of("door-1"));
    }

    @Test
    void aLoopOfThreeNodesMixingParentAndSurfaceRelationsIsRefused() {
        // w2 hangs below w1 (parent); w3 rests on w2 (surface): w3 needs w2 needs w1
        SemanticPlan plan = planOfNodes(chainWall("w1", null, abs(0, 0, 0)), chainWall("w2", "w1", abs(0, 0, 0)),
                chainWall("w3", null, abs(0, 0, 0)));
        PatchResult twoLinks = patcher.apply(plan, patch(1, new PlanOp.MoveNode("w3", onWall("w2"))));
        assertTrue(twoLinks.ok(), twoLinks.issues().toString());
        // w1 resting on w3 would close w1 -> w3 -> w2 -> w1 (surface, surface, parent)
        assertSingleIssue(patcher.apply(twoLinks.plan(), patch(2, new PlanOp.MoveNode("w1", onWall("w3")))),
                IssueCode.E_ANCHOR, "E-ANCHOR:w1#cycle", List.of("w1"));
        // a chain of two parents and a surface: w3 hangs below w2 below w1, so w1 cannot rest on w3
        SemanticPlan parents = planOfNodes(chainWall("w1", null, abs(0, 0, 0)), chainWall("w2", "w1", abs(0, 0, 0)),
                chainWall("w3", "w2", abs(0, 0, 0)));
        assertSingleIssue(patcher.apply(parents, patch(1, new PlanOp.MoveNode("w1", onWall("w3")))),
                IssueCode.E_ANCHOR, "E-ANCHOR:w1#cycle", List.of("w1"));
        // the other direction is fine: the leaf resting on the root closes nothing
        assertTrue(patcher.apply(parents, patch(1, new PlanOp.MoveNode("w3", onWall("w1")))).ok());
    }

    @Test
    void aLoopThroughTheParentOfThePartBeingMovedIsRefused() {
        // the hut cannot rest on its own wall: the wall hangs below the hut
        SemanticPlan plan = hut(patcher);
        assertSingleIssue(patcher.apply(plan, patch(1, new PlanOp.MoveNode("hut", onWall("wall-n")))),
                IssueCode.E_ANCHOR, "E-ANCHOR:hut#cycle", List.of("hut"));
    }

    @Test
    void aLongParentChainIsWalkedWithoutRecursionOrDelay() {
        List<PlanOp> ops = new ArrayList<>();
        for (int i = 0; i < LONG_CHAIN; i++) {
            ops.add(new PlanOp.AddNode(chainWall("w" + i, i == 0 ? null : "w" + (i - 1), abs(0, 0, 0))));
        }
        SemanticPlan chain = assertTimeoutPreemptively(Duration.ofSeconds(20),
                () -> patcher.apply(SemanticPlan.empty("plan"), new PlanPatch("p-0", 0, "test", ops)).plan());
        String last = "w" + (LONG_CHAIN - 1);
        // the head resting on the far end of the chain would close a loop of LONG_CHAIN parents and one surface
        PatchResult refused = assertTimeoutPreemptively(Duration.ofSeconds(20),
                () -> patcher.apply(chain, patch(1, new PlanOp.MoveNode("w0", onWall(last)))));
        assertSingleIssue(refused, IssueCode.E_ANCHOR, "E-ANCHOR:w0#cycle", List.of("w0"));
        // the far end resting on the head closes nothing: the head depends on nothing
        PatchResult accepted = assertTimeoutPreemptively(Duration.ofSeconds(20),
                () -> patcher.apply(chain, patch(1, new PlanOp.MoveNode(last, onWall("w0")))));
        assertTrue(accepted.ok(), accepted.issues().toString());
    }

    @Test
    void aLongSurfaceChainIsWalkedWithoutRecursionOrDelay() {
        List<PlanOp> ops = new ArrayList<>();
        for (int i = 0; i < LONG_CHAIN; i++) {
            ops.add(new PlanOp.AddNode(chainWall("w" + i, null, i == 0 ? abs(0, 0, 0) : onWall("w" + (i - 1)))));
        }
        SemanticPlan chain = assertTimeoutPreemptively(Duration.ofSeconds(20),
                () -> patcher.apply(SemanticPlan.empty("plan"), new PlanPatch("p-0", 0, "test", ops)).plan());
        String last = "w" + (LONG_CHAIN - 1);
        PatchResult refused = assertTimeoutPreemptively(Duration.ofSeconds(20),
                () -> patcher.apply(chain, patch(1, new PlanOp.MoveNode("w0", onWall(last)))));
        assertSingleIssue(refused, IssueCode.E_ANCHOR, "E-ANCHOR:w0#cycle", List.of("w0"));
        PatchResult accepted = assertTimeoutPreemptively(Duration.ofSeconds(20),
                () -> patcher.apply(chain, patch(1, new PlanOp.MoveNode(last, onWall("w0")))));
        assertTrue(accepted.ok(), accepted.issues().toString());
    }

    @Test
    void aMixedLoopThatWasAlreadyThereDoesNotSpinThePatcher() {
        // w1 hangs below w2 and rests on it; w2 hangs below w1: a loop the patcher never let in, in a hand-built plan
        SemanticPlan looped = new SemanticPlan(1, "p", 1, 0, null, StyleSpec.EMPTY,
                List.of(chainWall("w1", "w2", onWall("w2")), chainWall("w2", "w1", abs(0, 0, 0)),
                        chainWall("w3", null, abs(0, 0, 0))),
                List.of(), null, Provenance.NONE);
        PatchResult r = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> patcher.apply(looped, patch(1, new PlanOp.MoveNode("w3", onWall("w1")))));
        assertTrue(r.ok(), r.issues().toString());
    }

    // ------------------------------------------------------------------ normalize adds nodes in dependency order

    /** A plan the patcher built with a real MoveNode: door-1 is stored BEFORE the wall-1 it then rests on. */
    private SemanticPlan doorOnALaterWall() {
        SemanticPlan plan = planOfNodes(chainDoor("door-1", null, abs(0, 0, 0)), chainWall("wall-1", null, abs(0, 0, 0)));
        PatchResult r = patcher.apply(plan, patch(1,
                new PlanOp.MoveNode("door-1", new Anchor.OnSurface("wall-1", Side.OUTER, 1, 0))));
        assertTrue(r.ok(), r.issues().toString());
        assertEquals(List.of("door-1", "wall-1"), nodeIds(r.plan()));
        return r.plan();
    }

    @Test
    void normalizeAcceptsAPlanWhoseDoorIsListedBeforeTheWallItRestsOn() {
        SemanticPlan built = doorOnALaterWall();
        PatchResult r = patcher.normalize(built);
        assertTrue(r.ok(), r.issues().toString());
        assertEquals(built.contentHash(), r.plan().contentHash());
        assertEquals(List.of("wall-1", "door-1"), nodeIds(r.plan()), "the nodes are added in dependency order");
        assertEquals(built.node("door-1").orElseThrow(), r.plan().node("door-1").orElseThrow());
    }

    @Test
    void normalizeAcceptsAFiveNodeChainWhereEveryNodeRestsOnAWallListedAfterIt() {
        SemanticPlan plan = planOfNodes(chainWall("w0", null, abs(0, 0, 0)), chainWall("w1", null, abs(0, 0, 0)),
                chainWall("w2", null, abs(0, 0, 0)), chainWall("w3", null, abs(0, 0, 0)), chainWall("w4", null, abs(0, 0, 0)));
        SemanticPlan chain = patcher.apply(plan, patch(1, new PlanOp.MoveNode("w0", onWall("w1")), new PlanOp.MoveNode("w1", onWall("w2")),
                new PlanOp.MoveNode("w2", onWall("w3")), new PlanOp.MoveNode("w3", onWall("w4")))).plan();
        assertEquals(List.of("w0", "w1", "w2", "w3", "w4"), nodeIds(chain));
        PatchResult r = patcher.normalize(chain);
        assertTrue(r.ok(), r.issues().toString());
        // hand-derived: only w4 needs nothing, so it goes first, then w3, w2, w1, w0
        assertEquals(List.of("w4", "w3", "w2", "w1", "w0"), nodeIds(r.plan()));
        assertEquals(chain.contentHash(), r.plan().contentHash());
    }

    @Test
    void normalizeAcceptsAChildListedBeforeItsParent() {
        SemanticPlan loose = new SemanticPlan(1, "p", 0, null, null, StyleSpec.EMPTY,
                List.of(wall("wall-n", abs(0, 0, 0)), node("hut", "micra:structure", null, Map.of())), List.of(), null, Provenance.NONE);
        PatchResult r = patcher.normalize(loose);
        assertTrue(r.ok(), r.issues().toString());
        assertEquals(List.of("hut", "wall-n"), nodeIds(r.plan()));
    }

    @Test
    void normalizeKeepsTheNodeOrderOfAPlanThatIsAlreadyInDependencyOrder() {
        PlanNode door = new PlanNode("d", "micra:door", "hut", new Anchor.OnSurface("wall-n", Side.OUTER, 3, 0), Map.of(), Set.of(), "");
        PlanNode pillar = node("pillar-1", "micra:pillar", null, Map.of());
        SemanticPlan plan = patcher.apply(hut(patcher), patch(1, new PlanOp.AddNode(door), new PlanOp.AddNode(pillar))).plan();
        assertEquals(List.of("hut", "wall-n", "d", "pillar-1"), nodeIds(plan));
        PatchResult r = patcher.normalize(plan);
        assertTrue(r.ok(), r.issues().toString());
        assertEquals(List.of("hut", "wall-n", "d", "pillar-1"), nodeIds(r.plan()));
        assertEquals(plan.nodes(), r.plan().nodes());
    }

    @Test
    void normalizeRefusesALoopInALoosePlanQuicklyAndWithoutAnException() {
        // wall-1 hangs below door-1 and door-1 rests on wall-1: neither can be added first
        SemanticPlan loose = new SemanticPlan(1, "p", 0, null, null, StyleSpec.EMPTY,
                List.of(chainWall("wall-1", "door-1", abs(0, 0, 0)), chainDoor("door-1", null, onWall("wall-1"))),
                List.of(), null, Provenance.NONE);
        PatchResult r = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> patcher.normalize(loose));
        assertFalse(r.ok());
        // hand-derived: the nodes that cannot be placed are added in the plan's own order, so wall-1 is refused for its
        // missing parent and door-1 for its missing wall
        assertEquals(List.of("E-ANCHOR:wall-1#parent", "E-ANCHOR:door-1#anchor"), r.issues().stream().map(Issue::id).toList());
    }

    @Test
    void aPlanThatWentThroughJsonComesBackThroughNormalizeWithTheSameHash() {
        SemanticPlan built = doorOnALaterWall();
        String json = MiniJson.write(PlanJson.toTree(built));
        SemanticPlan loose = PlanJson.planFromTree(MiniJson.parse(json));
        assertEquals(List.of("door-1", "wall-1"), nodeIds(loose), "the JSON keeps the stored order");
        PatchResult r = patcher.normalize(loose);
        assertTrue(r.ok(), r.issues().toString());
        assertEquals(built.contentHash(), r.plan().contentHash());
        assertEquals(List.of("wall-1", "door-1"), nodeIds(r.plan()));
    }

    // ------------------------------------------------------------------ rules that were only checked by their code

    @Test
    void updatingTheParametersOfAModuleInstanceIsRefusedNamingTheType() {
        SemanticPlan plan = patcher.apply(SemanticPlan.empty("p"),
                patch(0, new PlanOp.AddNode(node("line-1", "mod:test_line", null, Map.of())))).plan();
        PatchResult r = patcher.apply(plan, patch(1, new PlanOp.UpdateParams("line-1", Map.of("n", new IntV(1)))));
        assertSingleIssue(r, IssueCode.E_PARAM_RANGE, "E-PARAM-RANGE:line-1#params", List.of("line-1"));
        assertTrue(r.issues().get(0).message().contains("mod:test_line"), r.issues().get(0).message());
    }

    private static void assertRemovalRefused(PatchResult r, String removedId, String dependents) {
        assertSingleIssue(r, IssueCode.E_ANCHOR, "E-ANCHOR:" + removedId + "#remove", List.of(removedId));
        Issue issue = r.issues().get(0);
        assertEquals(dependents, issue.data().get("dependents"));
        assertEquals(List.of(new FixHint("REMOVE_FIRST", Map.of("ids", dependents))), issue.hints());
    }

    /** Three machine parts m (motor), s and r (shafts) at revision 1, plus whatever the extra operations add. */
    private SemanticPlan machinePlan(PlanOp... more) {
        List<PlanOp> ops = new ArrayList<>(List.of(
                new PlanOp.AddNode(node("m", "test:motor", null, Map.of())),
                new PlanOp.AddNode(node("s", "test:shaft", null, Map.of())),
                new PlanOp.AddNode(node("r", "test:shaft", null, Map.of()))));
        ops.addAll(List.of(more));
        PatchResult r = patcher.apply(SemanticPlan.empty("p"), new PlanPatch("p-0", 0, "test", ops));
        assertTrue(r.ok(), r.issues().toString());
        return r.plan();
    }

    private static Connection connection(String id, Routing routing, Constraints constraints) {
        return new Connection(id, new PortRef("m", "out"), new PortRef("s", "in"), ConnKind.ROTATION, routing, constraints);
    }

    @Test
    void removingAWallWithSomethingOnItsSurfaceNamesTheDependent() {
        PlanNode door = new PlanNode("d", "micra:door", "hut", new Anchor.OnSurface("wall-n", Side.OUTER, 3, 0), Map.of(), Set.of(), "");
        SemanticPlan plan = patcher.apply(hut(patcher), patch(1, new PlanOp.AddNode(door))).plan();
        assertRemovalRefused(patcher.apply(plan, patch(2, new PlanOp.RemoveNode("wall-n"))), "wall-n", "d");
    }

    @Test
    void removingEitherEndOfAConnectionNamesTheConnection() {
        SemanticPlan plan = machinePlan(new PlanOp.AddConnection(connection("c-1", Routing.AUTO, Constraints.NONE)));
        assertRemovalRefused(patcher.apply(plan, patch(1, new PlanOp.RemoveNode("m"))), "m", "c-1");
        assertRemovalRefused(patcher.apply(plan, patch(1, new PlanOp.RemoveNode("s"))), "s", "c-1");
        // a part the connection does not touch can go
        assertTrue(patcher.apply(plan, patch(1, new PlanOp.RemoveNode("r"))).ok());
    }

    @Test
    void removingANodeAConnectionRoutesThroughNamesTheConnection() {
        SemanticPlan plan = machinePlan(new PlanOp.AddConnection(
                connection("c-via", new Routing.Explicit(List.of("r")), Constraints.NONE)));
        assertRemovalRefused(patcher.apply(plan, patch(1, new PlanOp.RemoveNode("r"))), "r", "c-via");
    }

    @Test
    void removingANodeAConnectionAvoidsNamesTheConnection() {
        SemanticPlan plan = machinePlan(new PlanOp.AddConnection(
                connection("c-avoid", Routing.AUTO, new Constraints(null, Set.of("r"), null, Set.of()))));
        assertRemovalRefused(patcher.apply(plan, patch(1, new PlanOp.RemoveNode("r"))), "r", "c-avoid");
    }

    private static LogisticsPlan.Dock dock(String id, List<PortRef> linkedPorts, List<String> connectorNodeIds) {
        return new LogisticsPlan.Dock(id, new Box(0, 0, 0, 1, 0, 1), new Box(0, 1, 0, 1, 4, 1), Facing.NORTH,
                linkedPorts, connectorNodeIds);
    }

    private static LogisticsPlan.Dock dock(String id) {
        return dock(id, List.of(), List.of());
    }

    @Test
    void removingANodeAPortLinkedToADockNamesTheDock() {
        LogisticsPlan logistics = new LogisticsPlan(List.of(dock("dock-a", List.of(new PortRef("m", "out")), List.of())),
                List.of(), List.of());
        SemanticPlan plan = machinePlan(new PlanOp.SetLogistics(logistics));
        assertRemovalRefused(patcher.apply(plan, patch(1, new PlanOp.RemoveNode("m"))), "m", "dock-a");
        assertTrue(patcher.apply(plan, patch(1, new PlanOp.RemoveNode("s"))).ok());
    }

    @Test
    void removingANodeADockingConnectorUsesNamesTheDock() {
        LogisticsPlan logistics = new LogisticsPlan(List.of(dock("dock-b", List.of(), List.of("r"))), List.of(), List.of());
        SemanticPlan plan = machinePlan(new PlanOp.SetLogistics(logistics));
        assertRemovalRefused(patcher.apply(plan, patch(1, new PlanOp.RemoveNode("r"))), "r", "dock-b");
        assertTrue(patcher.apply(plan, patch(1, new PlanOp.RemoveNode("s"))).ok());
    }

    private PatchResult setLogistics(LogisticsPlan.Dock... docks) {
        return patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.SetLogistics(
                new LogisticsPlan(List.of(docks), List.of(), List.of()))));
    }

    @Test
    void aDockPortThatTheScriptTextCannotCarryIsRefusedNamingTheDock() {
        // "node.port" is split at the FIRST dot and both halves must be non-empty, so a node id with a dot or
        // without characters cannot be written into a script and read back
        List<PortRef> unwritable = List.of(new PortRef("a.b", "c"), new PortRef("", "c"), new PortRef("a", ""),
                new PortRef("Bad_Id", "c"), new PortRef("a".repeat(49), "c"), new PortRef(".", "c"));
        for (PortRef bad : unwritable) {
            assertSingleIssue(setLogistics(dock("dock-a", List.of(bad), List.of())),
                    IssueCode.E_CONN_INVALID, "E-CONN-INVALID:dock-a#port:0", List.of("dock-a"));
        }
    }

    @Test
    void aDockPortWithAWellFormedNodeIdIsAcceptedEvenWhenTheNameHasADot() {
        // the split is at the first dot, so a port NAME may contain more dots; the node need not exist yet
        List<PortRef> writable = List.of(new PortRef("m", "out"), new PortRef("m", "out.left"), new PortRef("m", "a b"),
                new PortRef("n".repeat(48), "p"), new PortRef("a-1", "."));
        for (PortRef ok : writable) {
            PatchResult r = setLogistics(dock("dock-a", List.of(ok), List.of()));
            assertTrue(r.ok(), ok + " " + r.issues());
        }
    }

    @Test
    void everyUnwritableDockPortIsReportedWithItsPositionInThatDock() {
        PortRef bad = new PortRef("a.b", "c");
        PortRef good = new PortRef("m", "out");
        PatchResult r = setLogistics(dock("dock-a", List.of(bad, good, bad), List.of()), dock("dock-b", List.of(good), List.of()),
                dock("dock-c", List.of(new PortRef("m", "")), List.of()));
        assertNull(r.plan(), "the whole logistics plan is refused");
        assertEquals(List.of("E-CONN-INVALID:dock-a#port:0", "E-CONN-INVALID:dock-a#port:2", "E-CONN-INVALID:dock-c#port:0"),
                r.issues().stream().map(Issue::id).toList());
    }

    @Test
    void theMessageOfAnUnwritableDockPortNamesTheDockAndNeverQuotesAHugeValue() {
        String huge = "x".repeat(100_000);
        PatchResult r = setLogistics(dock("dock-a", List.of(new PortRef(huge, "c")), List.of()));
        assertEquals(1, r.issues().size(), r.issues().toString());
        Issue issue = r.issues().get(0);
        assertTrue(issue.message().contains("dock-a"), issue.message());
        assertTrue(issue.message().length() < 400, "message length " + issue.message().length());
        assertFalse(issue.message().contains("x".repeat(100)), "the value is cut, not quoted whole");
    }

    @Test
    void aRepeatedRouteIdIsRefused() {
        LogisticsPlan repeated = new LogisticsPlan(List.of(dock("d")), List.of(
                new LogisticsPlan.Route("r-1", "d", "d", List.of(), null),
                new LogisticsPlan.Route("r-1", "d", "d", List.of(), null)), List.of());
        assertSingleIssue(patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.SetLogistics(repeated))),
                IssueCode.E_ID_DUPLICATE, "E-ID-DUPLICATE:r-1", List.of("r-1"));
    }

    @Test
    void aRouteOrCargoFlowPointingAtAMissingDockIsRefusedForEachEnd() {
        LogisticsPlan flowTo = new LogisticsPlan(List.of(dock("d")), List.of(),
                List.of(new LogisticsPlan.CargoFlow("minecraft:iron_ingot", 30.0, "d", "ghost")));
        assertSingleIssue(patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.SetLogistics(flowTo))),
                IssueCode.E_CONN_INVALID, "E-CONN-INVALID:minecraft:iron_ingot#dock:ghost", List.of("minecraft:iron_ingot"));

        LogisticsPlan flowFrom = new LogisticsPlan(List.of(dock("d")), List.of(),
                List.of(new LogisticsPlan.CargoFlow("minecraft:iron_ingot", 30.0, "ghost", "d")));
        assertSingleIssue(patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.SetLogistics(flowFrom))),
                IssueCode.E_CONN_INVALID, "E-CONN-INVALID:minecraft:iron_ingot#dock:ghost", List.of("minecraft:iron_ingot"));

        LogisticsPlan routeFrom = new LogisticsPlan(List.of(dock("d")),
                List.of(new LogisticsPlan.Route("r-1", "ghost", "d", List.of(), null)), List.of());
        assertSingleIssue(patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.SetLogistics(routeFrom))),
                IssueCode.E_CONN_INVALID, "E-CONN-INVALID:r-1#dock:ghost", List.of("r-1"));

        LogisticsPlan bothMissing = new LogisticsPlan(List.of(dock("d")), List.of(),
                List.of(new LogisticsPlan.CargoFlow("minecraft:iron_ingot", 30.0, "ghost-a", "ghost-b")));
        PatchResult r = patcher.apply(SemanticPlan.empty("p"), patch(0, new PlanOp.SetLogistics(bothMissing)));
        assertEquals(List.of("E-CONN-INVALID:minecraft:iron_ingot#dock:ghost-a", "E-CONN-INVALID:minecraft:iron_ingot#dock:ghost-b"),
                r.issues().stream().map(Issue::id).toList());
    }

    @Test
    void connectionIdsFollowTheSameShapeRuleAsNodeIds() {
        SemanticPlan plan = machinePlan();
        Connection badId = connection("Bad_Id", Routing.AUTO, Constraints.NONE);
        assertSingleIssue(patcher.apply(plan, patch(1, new PlanOp.AddConnection(badId))),
                IssueCode.E_ID_INVALID, "E-ID-INVALID:Bad_Id", List.of("Bad_Id"));
        String tooLong = "c".repeat(49);
        assertSingleIssue(patcher.apply(plan, patch(1, new PlanOp.AddConnection(connection(tooLong, Routing.AUTO, Constraints.NONE)))),
                IssueCode.E_ID_INVALID, "E-ID-INVALID:" + tooLong, List.of(tooLong));
        // 48 characters is the longest id, for connections and for nodes alike
        assertTrue(patcher.apply(plan, patch(1, new PlanOp.AddConnection(connection("c".repeat(48), Routing.AUTO, Constraints.NONE)))).ok());
        assertTrue(patcher.apply(plan, patch(1, new PlanOp.AddNode(node("n".repeat(48), "micra:pillar", null, Map.of())))).ok());
    }

    @Test
    void movingToAPositionOutsideTheAllowedRangeIsRefused() {
        SemanticPlan plan = hut(patcher);
        int max = PlanPatcher.MAX_COORD;
        assertSingleIssue(patcher.apply(plan, patch(1, new PlanOp.MoveNode("wall-n", abs(max + 1, 0, 0)))),
                IssueCode.E_PARAM_RANGE, "E-PARAM-RANGE:wall-n#anchor", List.of("wall-n"));
        assertSingleIssue(patcher.apply(plan, patch(1, new PlanOp.MoveNode("wall-n", abs(0, -max - 1, 0)))),
                IssueCode.E_PARAM_RANGE, "E-PARAM-RANGE:wall-n#anchor", List.of("wall-n"));
        assertSingleIssue(patcher.apply(plan, patch(1, new PlanOp.MoveNode("wall-n", abs(0, 0, Integer.MAX_VALUE)))),
                IssueCode.E_PARAM_RANGE, "E-PARAM-RANGE:wall-n#anchor", List.of("wall-n"));
        // the limit itself is allowed, on either side
        assertTrue(patcher.apply(plan, patch(1, new PlanOp.MoveNode("wall-n", abs(max, -max, max)))).ok());
    }

    @Test
    void aWallCanCarryThingsOnItsInnerFaceToo() {
        SemanticPlan plan = hut(patcher);
        PlanNode inner = new PlanNode("d", "micra:door", "hut", new Anchor.OnSurface("wall-n", Side.INNER, 3, 0), Map.of(), Set.of(), "");
        PatchResult r = patcher.apply(plan, patch(1, new PlanOp.AddNode(inner)));
        assertTrue(r.ok(), r.issues().toString());
        assertEquals(new Anchor.OnSurface("wall-n", Side.INNER, 3, 0), r.plan().node("d").orElseThrow().anchor());
    }

    @Test
    void thingsCanOnlyBePutOnTheSurfaceOfTheRegistrysWallPart() {
        // the id the patcher checks against is the real wall part of the registry, spelled as the AI writes it
        assertEquals("micra:wall", BuildingParts.WALL);
        assertTrue(TestParts.registry().contains(BuildingParts.WALL));
        SemanticPlan plan = hut(patcher);
        PlanNode onPillar = new PlanNode("d", "micra:door", "hut", new Anchor.OnSurface("pillar-1", Side.OUTER, 1, 0), Map.of(), Set.of(), "");
        SemanticPlan withPillar = patcher.apply(plan, patch(1, new PlanOp.AddNode(node("pillar-1", "micra:pillar", null, Map.of())))).plan();
        assertSingleIssue(patcher.apply(withPillar, patch(2, new PlanOp.AddNode(onPillar))),
                IssueCode.E_ANCHOR, "E-ANCHOR:d#anchor", List.of("d"));
    }

    @Test
    void surfacePositionsRunFromZeroUpToTheAllowedRange() {
        SemanticPlan plan = hut(patcher);
        int max = PlanPatcher.MAX_COORD;
        for (int[] uv : new int[][] {{0, -1}, {-1, 0}, {max + 1, 0}, {0, max + 1}}) {
            PlanNode bad = new PlanNode("d", "micra:door", "hut", new Anchor.OnSurface("wall-n", Side.OUTER, uv[0], uv[1]), Map.of(), Set.of(), "");
            assertSingleIssue(patcher.apply(plan, patch(1, new PlanOp.AddNode(bad))),
                    IssueCode.E_ANCHOR, "E-ANCHOR:d#anchor", List.of("d"));
        }
        PlanNode edge = new PlanNode("d", "micra:door", "hut", new Anchor.OnSurface("wall-n", Side.OUTER, max, max), Map.of(), Set.of(), "");
        assertTrue(patcher.apply(plan, patch(1, new PlanOp.AddNode(edge))).ok());
    }

    @Test
    void aStalePatchNamesBothRevisionsAndHowToRebase() {
        SemanticPlan plan = hut(patcher);
        PatchResult r = patcher.apply(plan, patch(0, new PlanOp.RemoveNode("wall-n")));
        assertSingleIssue(r, IssueCode.E_PATCH_STALE, "E-PATCH-STALE:p-0", List.of("p-0"));
        Issue issue = r.issues().get(0);
        assertEquals(Map.of("baseRevision", "0", "revision", "1"), issue.data());
        assertEquals(List.of(new FixHint("REBASE", Map.of("revision", "1"))), issue.hints());
    }

    @Test
    void normalizeKeepsTheRevisionParentRevisionSchemaAndProvenanceOfTheInput() {
        Provenance provenance = new Provenance("stage-2", "model-x", "prompt-hash", List.of("img-1"), 1234L);
        // a schema version other than the current one, so that a hard-coded default would show
        SemanticPlan plan = new SemanticPlan(2, "keep-me", 7, 5, null, StyleSpec.EMPTY,
                List.of(node("hut", "micra:structure", null, Map.of("width", new NumV(9.0)))), List.of(), null, provenance);
        PatchResult r = patcher.normalize(plan);
        assertTrue(r.ok(), r.issues().toString());
        assertEquals(2, r.plan().schemaVersion());
        assertEquals("keep-me", r.plan().planId());
        assertEquals(7, r.plan().revision());
        assertEquals(5, r.plan().parentRevision());
        assertEquals(provenance, r.plan().provenance());
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
