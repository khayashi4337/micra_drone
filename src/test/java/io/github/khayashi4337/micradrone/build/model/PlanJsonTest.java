package io.github.khayashi4337.micradrone.build.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.ParamValue.BoolV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.EnumV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.IntV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.ListV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.MaterialV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.NumV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.StrV;
import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class PlanJsonTest {
    static Site site() {
        return new Site("minecraft:overworld", new BuildFrame(new IntPos(100, 64, 200), Facing.EAST),
                new Box(-2, -3, -2, 12, 8, 12), "", "");
    }

    /** Loosely typed (Int/Str/Bool/List only), so a JSON round trip gives back equal records. */
    static SemanticPlan loosePlan() {
        PlanNode structure = new PlanNode("hut", "micra:structure", null,
                new Anchor.Absolute(new LocalPos(0, 0, 0), Rot.NONE),
                Map.of("width", new IntV(7), "depth", new IntV(7)), Set.of("main", "a"), "小屋 \"one\"\n");
        PlanNode wall = new PlanNode("wall-n", "micra:wall", "hut",
                new Anchor.Absolute(new LocalPos(0, 0, 0), Rot.NONE),
                Map.of("side", new StrV("north"), "holes", new ListV(List.of(new IntV(1), new IntV(2)))), Set.of(), "");
        PlanNode door = new PlanNode("door-1", "micra:door", "hut",
                new Anchor.OnSurface("wall-n", Side.OUTER, 3, 0), Map.of("kind", new StrV("single"), "lattice", new BoolV(true)),
                Set.of(), null);
        PlanNode press = new PlanNode("press-1", "create:mechanical_press", null,
                new Anchor.Absolute(new LocalPos(3, 1, 2), new Rot(1, true)), Map.of(), Set.of(), "");
        PlanNode module = new PlanNode("line-1", "mod:press_station", null,
                new Anchor.InSlot("slot-a", new Rot(2, false)), Map.of(), Set.of(), "");
        Connection explicit = new Connection("c-1", new PortRef("press-1", "power_in"), new PortRef("line-1", "out"),
                ConnKind.ROTATION, new Routing.Explicit(List.of("shaft-1", "shaft-2")),
                new Constraints(20, Set.of("wall-n"), 3, Set.of(Dir6.UP, Dir6.NORTH)));
        Connection auto = new Connection("c-2", new PortRef("press-1", "item_out"), new PortRef("line-1", "in"),
                ConnKind.ITEM, Routing.AUTO, Constraints.NONE);
        LogisticsPlan logistics = new LogisticsPlan(
                List.of(new LogisticsPlan.Dock("dock-1", new Box(0, 0, 0, 8, 0, 8), new Box(0, 1, 0, 8, 16, 8), Facing.NORTH,
                        List.of(new PortRef("press-1", "item_out")), List.of("conn-1"))),
                List.of(new LogisticsPlan.Route("route-1", "dock-1", "dock-1", List.of(new LocalPos(0, 5, 0), new LocalPos(9, 5, 9)), "mod:airship_a")),
                List.of(new LogisticsPlan.CargoFlow("create:iron_sheet", 12.5, "dock-1", "dock-1")));
        return new SemanticPlan(SemanticPlan.SCHEMA_VERSION, "plan-1", 3, 2, site(),
                new StyleSpec(Map.of("roof", "minecraft:red_nether_bricks", "wall", "minecraft:stone_bricks"), Set.of("cozy")),
                List.of(structure, wall, door, press, module), List.of(explicit, auto), logistics,
                new Provenance("architect", "claude-x", "abc", List.of("img-1"), 1234L));
    }

    @Test
    void jsonRoundTripGivesEqualRecords() {
        SemanticPlan plan = loosePlan();
        String json = MiniJson.write(PlanJson.toTree(plan));
        SemanticPlan back = PlanJson.planFromTree(MiniJson.parse(json));
        assertEquals(plan, back);
        assertEquals(plan.contentHash(), back.contentHash());
    }

    @Test
    void contentHashIgnoresMetaAndOrderButNotContent() {
        SemanticPlan plan = loosePlan();
        String base = plan.contentHash();
        SemanticPlan meta = new SemanticPlan(1, "other-id", 99, null, plan.site(), plan.style(), plan.nodes(),
                plan.connections(), plan.logistics(), Provenance.NONE);
        assertEquals(base, meta.contentHash(), "planId/revision/provenance are not part of the content");

        List<PlanNode> reversed = new ArrayList<>(plan.nodes());
        Collections.reverse(reversed);
        List<Connection> reversedConns = new ArrayList<>(plan.connections());
        Collections.reverse(reversedConns);
        SemanticPlan reordered = new SemanticPlan(1, "plan-1", 3, 2, plan.site(), plan.style(), reversed, reversedConns,
                plan.logistics(), plan.provenance());
        assertEquals(base, reordered.contentHash(), "node/connection order must not matter");

        TreeMap<String, ParamValue> changed = new TreeMap<>(plan.nodes().get(0).params());
        changed.put("width", new IntV(8));
        List<PlanNode> edited = new ArrayList<>(plan.nodes());
        PlanNode first = edited.get(0);
        edited.set(0, new PlanNode(first.id(), first.type(), first.parent(), first.anchor(), changed, first.tags(), first.label()));
        SemanticPlan different = new SemanticPlan(1, "plan-1", 3, 2, plan.site(), plan.style(), edited, plan.connections(),
                plan.logistics(), plan.provenance());
        assertNotEquals(base, different.contentHash());
    }

    @Test
    void typedAndLooseParametersHashTheSameWhenTheTextIsTheSame() {
        PlanNode loose = new PlanNode("w", "micra:wall", null, new Anchor.Absolute(new LocalPos(0, 0, 0), Rot.NONE),
                Map.of("side", new StrV("north"), "material", new StrV("wall")), Set.of(), "");
        PlanNode typed = new PlanNode("w", "micra:wall", null, new Anchor.Absolute(new LocalPos(0, 0, 0), Rot.NONE),
                Map.of("side", new EnumV("north"), "material", new MaterialV("wall")), Set.of(), "");
        assertEquals(CanonicalJson.write(PlanJson.nodeToTree(loose)), CanonicalJson.write(PlanJson.nodeToTree(typed)));
    }

    @Test
    void emptyPlanHasNoSiteAndAStableHash() {
        SemanticPlan empty = SemanticPlan.empty("p");
        assertEquals(0, empty.revision());
        assertEquals(null, empty.site());
        assertEquals(empty.contentHash(), SemanticPlan.empty("q").contentHash());
        SemanticPlan back = PlanJson.planFromTree(MiniJson.parse(MiniJson.write(PlanJson.toTree(empty))));
        assertEquals(empty, back);
    }

    @Test
    void paramValueFromTreeReadsLooselyAndRejectsBadInput() {
        assertEquals(new IntV(3), ParamValue.fromTree(3.0));
        assertEquals(new IntV(Integer.MIN_VALUE), ParamValue.fromTree((double) Integer.MIN_VALUE));
        assertEquals(new NumV(3.5), ParamValue.fromTree(3.5));
        assertEquals(new NumV(3_000_000_000.0), ParamValue.fromTree(3_000_000_000.0), "outside int range stays a number");
        assertEquals(new StrV("x"), ParamValue.fromTree("x"));
        assertEquals(new BoolV(true), ParamValue.fromTree(true));
        assertEquals(new ListV(List.of(new IntV(1), new StrV("a"))), ParamValue.fromTree(List.of(1.0, "a")));
        assertThrows(IllegalArgumentException.class, () -> ParamValue.fromTree(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> ParamValue.fromTree(Map.of("a", 1)));
        assertThrows(IllegalArgumentException.class, () -> ParamValue.fromTree(null));
        assertThrows(IllegalArgumentException.class, () -> new NumV(Double.POSITIVE_INFINITY));
    }

    @Test
    void decodingErrorsCarryAJsonPath() {
        PlanJsonException missing = assertThrows(PlanJsonException.class,
                () -> PlanJson.planFromTree(MiniJson.parse("{\"schemaVersion\":1}")));
        assertTrue(missing.getMessage().contains("$"), missing.getMessage());

        String badPos = MiniJson.write(PlanJson.toTree(loosePlan())).replace("\"pos\":[0,0,0]", "\"pos\":[0,0]");
        PlanJsonException e = assertThrows(PlanJsonException.class, () -> PlanJson.planFromTree(MiniJson.parse(badPos)));
        assertTrue(e.getMessage().contains("$.nodes["), e.getMessage());
        assertTrue(e.getMessage().contains("pos"), e.getMessage());

        String wrongVersion = MiniJson.write(PlanJson.toTree(loosePlan())).replace("\"schemaVersion\":1", "\"schemaVersion\":2");
        PlanJsonException v = assertThrows(PlanJsonException.class, () -> PlanJson.planFromTree(MiniJson.parse(wrongVersion)));
        assertTrue(v.getMessage().contains("schemaVersion"), v.getMessage());
    }

    @Test
    void patchRoundTripCoversEveryOperation() {
        PlanNode node = new PlanNode("hut", "micra:structure", null, new Anchor.Absolute(new LocalPos(0, 0, 0), Rot.NONE),
                Map.of("width", new IntV(7)), Set.of(), "");
        Connection conn = new Connection("c-1", new PortRef("a", "out"), new PortRef("b", "in"), ConnKind.ITEM,
                Routing.AUTO, Constraints.NONE);
        PlanPatch patch = new PlanPatch("patch-1", 4, "architect", List.of(
                new PlanOp.SetSite(site()),
                new PlanOp.SetStyle(new StyleSpec(Map.of("roof", "minecraft:bricks"), Set.of("m"))),
                new PlanOp.AddNode(node),
                new PlanOp.UpdateParams("hut", Map.of("width", new IntV(9))),
                new PlanOp.MoveNode("hut", new Anchor.Absolute(new LocalPos(1, 0, 1), new Rot(1, false))),
                new PlanOp.AddConnection(conn),
                new PlanOp.RemoveConnection("c-1"),
                new PlanOp.RemoveNode("hut"),
                new PlanOp.SetLogistics(null)));
        PlanPatch back = PlanJson.patchFromTree(MiniJson.parse(MiniJson.write(PlanJson.toTree(patch))));
        assertEquals(patch, back);
    }

    @Test
    void handWrittenPatchJsonIsReadable() {
        String json = "{\"patchId\":\"p\",\"baseRevision\":0,\"stageId\":\"hand\",\"ops\":["
                + "{\"op\":\"add_node\",\"node\":{\"id\":\"hut\",\"type\":\"micra:structure\",\"parent\":null,"
                + "\"anchor\":{\"kind\":\"absolute\",\"pos\":[0,0,0],\"rot\":{\"turns\":0,\"mirror\":false}},"
                + "\"params\":{\"width\":7},\"tags\":[],\"label\":\"\"}}]}";
        PlanPatch patch = PlanJson.patchFromTree(MiniJson.parse(json));
        assertEquals(1, patch.ops().size());
        PlanOp.AddNode add = (PlanOp.AddNode) patch.ops().get(0);
        assertEquals(new IntV(7), add.node().params().get("width"));
    }

    @Test
    void unknownOperationIsRejectedWithItsPath() {
        String json = "{\"patchId\":\"p\",\"baseRevision\":0,\"stageId\":\"s\",\"ops\":[{\"op\":\"explode\"}]}";
        PlanJsonException e = assertThrows(PlanJsonException.class, () -> PlanJson.patchFromTree(MiniJson.parse(json)));
        assertTrue(e.getMessage().contains("$.ops[0]"), e.getMessage());
    }
}
