package io.github.khayashi4337.micradrone.build.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    // ------------------------------------------------------------------ the content hash: written order, pinned values, sensitivity

    private static final String KEY_CONSTRAINTS = "constraints";
    private static final String KEY_ENTRY_DIRS = "entryDirs";
    private static final String KEY_LOGISTICS = "logistics";
    private static final String KEY_DOCKS = "docks";

    @Test
    void entryDirectionsAreWrittenInTheDictionaryOrderOfTheirNames() {
        // Dir6 is declared UP, DOWN, NORTH, EAST, SOUTH, WEST; the wire names sort as down, east, north, south, up, west
        Connection all = new Connection("c", new PortRef("a", "out"), new PortRef("b", "in"), ConnKind.ITEM, Routing.AUTO,
                new Constraints(null, Set.of(), null, EnumSet.allOf(Dir6.class)));
        Map<?, ?> constraints = assertInstanceOf(Map.class, PlanJson.connectionToTree(all).get(KEY_CONSTRAINTS));
        assertEquals(List.of("down", "east", "north", "south", "up", "west"), constraints.get(KEY_ENTRY_DIRS));
        Connection two = new Connection("c", new PortRef("a", "out"), new PortRef("b", "in"), ConnKind.ITEM, Routing.AUTO,
                new Constraints(null, Set.of(), null, Set.of(Dir6.UP, Dir6.NORTH)));
        assertTrue(CanonicalJson.write(PlanJson.connectionToTree(two)).contains("\"entryDirs\":[\"north\",\"up\"]"));
    }

    /** loosePlan() with its logistics replaced. */
    private static SemanticPlan withLogistics(SemanticPlan plan, LogisticsPlan logistics) {
        return new SemanticPlan(plan.schemaVersion(), plan.planId(), plan.revision(), plan.parentRevision(), plan.site(),
                plan.style(), plan.nodes(), plan.connections(), logistics, plan.provenance());
    }

    private static LogisticsPlan.Dock dock(String id) {
        return new LogisticsPlan.Dock(id, new Box(0, 0, 0, 8, 0, 8), new Box(0, 1, 0, 8, 16, 8), Facing.NORTH, List.of(), List.of());
    }

    private static LogisticsPlan.Route route(String id) {
        return new LogisticsPlan.Route(id, "dock-1", "dock-2", List.of(new LocalPos(0, 5, 0)), "mod:airship_a");
    }

    @Test
    void theOrderOfDocksRoutesAndFlowsDoesNotChangeTheHashButIsKeptInTheJson() {
        // Like nodes and connections, the lists are written in a fixed order for the hash: docks and routes by id, flows by
        // (item, from, to, perMin). The five flows differ from A in exactly one of those four, so a comparator that leaves
        // any one of them out lets the input order decide between two of them, and the reversed lists then hash differently.
        LogisticsPlan.CargoFlow a = new LogisticsPlan.CargoFlow("minecraft:iron", 12.5, "dock-1", "dock-2");
        LogisticsPlan.CargoFlow byRate = new LogisticsPlan.CargoFlow("minecraft:iron", 3.0, "dock-1", "dock-2");
        LogisticsPlan.CargoFlow byTo = new LogisticsPlan.CargoFlow("minecraft:iron", 12.5, "dock-1", "dock-1");
        LogisticsPlan.CargoFlow byFrom = new LogisticsPlan.CargoFlow("minecraft:iron", 12.5, "dock-2", "dock-2");
        LogisticsPlan.CargoFlow byItem = new LogisticsPlan.CargoFlow("minecraft:copper", 12.5, "dock-1", "dock-2");
        LogisticsPlan forward = new LogisticsPlan(List.of(dock("dock-1"), dock("dock-2")), List.of(route("route-1"), route("route-2")),
                List.of(a, byRate, byTo, byFrom, byItem));
        LogisticsPlan backward = new LogisticsPlan(List.of(dock("dock-2"), dock("dock-1")), List.of(route("route-2"), route("route-1")),
                List.of(byItem, byFrom, byTo, byRate, a));
        assertNotEquals(forward, backward, "the two plans differ only in list order");
        assertEquals(withLogistics(loosePlan(), forward).contentHash(), withLogistics(loosePlan(), backward).contentHash());

        // the JSON form of a plan keeps the order it was given (a round trip must give back the same lists)
        SemanticPlan back = PlanJson.planFromTree(MiniJson.parse(MiniJson.write(PlanJson.toTree(withLogistics(loosePlan(), backward)))));
        assertEquals(backward, back.logistics());
        Map<?, ?> logistics = assertInstanceOf(Map.class, PlanJson.toTree(withLogistics(loosePlan(), backward)).get(KEY_LOGISTICS));
        assertEquals("dock-2", ((Map<?, ?>) ((List<?>) logistics.get(KEY_DOCKS)).get(0)).get("id"));
    }

    @Test
    void aDockOrRouteWithoutAnIdIsWrittenBeforeTheNamedOnesInTheCanonicalTree() {
        // a hand-built plan may carry a null id; where it sorts decides the hash, so it is pinned (nulls first)
        LogisticsPlan logistics = new LogisticsPlan(List.of(dock("dock-1"), dock(null)), List.of(route("route-1"), route(null)), List.of());
        Map<?, ?> tree = assertInstanceOf(Map.class, PlanJson.contentTree(withLogistics(loosePlan(), logistics)).get(KEY_LOGISTICS));
        List<?> docks = assertInstanceOf(List.class, tree.get(KEY_DOCKS));
        assertNull(((Map<?, ?>) docks.get(0)).get("id"));
        assertEquals("dock-1", ((Map<?, ?>) docks.get(1)).get("id"));
        List<?> routes = assertInstanceOf(List.class, tree.get("routes"));
        assertNull(((Map<?, ?>) routes.get(0)).get("id"));
        assertEquals("route-1", ((Map<?, ?>) routes.get(1)).get("id"));
    }

    /**
     * The content hash of {@link #loosePlan()}. Not copied from this code's output: the canonical text of the plan was
     * written out by hand from its definition (keys in dictionary order, nodes and connections by id, entry directions
     * by wire name, no whitespace) and hashed with a separate SHA-256 implementation.
     */
    private static final String LOOSE_PLAN_HASH = "0fd74b89c104e64ea8ce76462120b9dc012444999b82490a60a823f3031809ff";

    @Test
    void theContentHashOfTheLoosePlanIsPinned() {
        assertEquals(LOOSE_PLAN_HASH, loosePlan().contentHash());
    }

    /** Three node kinds of anchor, a typed number and material, a mirrored turn, and Japanese text; given in an order that is not the id order. */
    static SemanticPlan smallPlan() {
        PlanNode dial = new PlanNode("z-dial", "test:dial", null, new Anchor.Absolute(new LocalPos(-1, 2, 3), new Rot(3, true)),
                Map.of("speed", new NumV(0.1), "lock", new BoolV(false)), Set.of("t"), "ダイヤル");
        PlanNode slot = new PlanNode("a-slot", "mod:x", "z-dial", new Anchor.InSlot("s1", Rot.NONE),
                Map.of("mat", new MaterialV("wall"), "kind", new EnumV("big")), Set.of(), "");
        PlanNode door = new PlanNode("m-door", "micra:door", null, new Anchor.OnSurface("z-dial", Side.INNER, 1, 2), Map.of(),
                Set.of(), "");
        Connection connection = new Connection("c", new PortRef("z-dial", "out"), new PortRef("a-slot", "in"), ConnKind.ROTATION,
                Routing.AUTO, Constraints.NONE);
        return new SemanticPlan(SemanticPlan.SCHEMA_VERSION, "small-1", 7, 6,
                new Site("minecraft:the_nether", new BuildFrame(new IntPos(-8, 70, 1000), Facing.NORTH), new Box(0, 0, 0, 9, 9, 9),
                        "td", "claim-1"),
                new StyleSpec(Map.of("wall", "minecraft:bricks"), Set.of("b", "a")), List.of(dial, slot, door), List.of(connection),
                null, Provenance.NONE);
    }

    /** The canonical text of {@link #smallPlan()} as the design rules give it (section 0 of the data model), worked out by hand. */
    private static final String SMALL_PLAN_CANONICAL = "{\"connections\":[{\"constraints\":{\"avoid\":[],\"entryDirs\":[],"
            + "\"maxLength\":null,\"maxTurns\":null},\"from\":{\"node\":\"z-dial\",\"port\":\"out\"},\"id\":\"c\","
            + "\"kind\":\"rotation\",\"routing\":{\"mode\":\"auto\"},\"to\":{\"node\":\"a-slot\",\"port\":\"in\"}}],"
            + "\"logistics\":null,\"nodes\":["
            + "{\"anchor\":{\"kind\":\"slot\",\"rot\":{\"mirror\":false,\"turns\":0},\"slot\":\"s1\"},\"id\":\"a-slot\","
            + "\"label\":\"\",\"params\":{\"kind\":\"big\",\"mat\":\"wall\"},\"parent\":\"z-dial\",\"tags\":[],\"type\":\"mod:x\"},"
            + "{\"anchor\":{\"kind\":\"surface\",\"node\":\"z-dial\",\"side\":\"inner\",\"u\":1,\"v\":2},\"id\":\"m-door\","
            + "\"label\":\"\",\"params\":{},\"parent\":null,\"tags\":[],\"type\":\"micra:door\"},"
            + "{\"anchor\":{\"kind\":\"absolute\",\"pos\":[-1,2,3],\"rot\":{\"mirror\":true,\"turns\":3}},\"id\":\"z-dial\","
            + "\"label\":\"ダイヤル\",\"params\":{\"lock\":false,\"speed\":0.1},\"parent\":null,\"tags\":[\"t\"],\"type\":\"test:dial\"}],"
            + "\"schemaVersion\":1,\"site\":{\"bounds\":[0,0,0,9,9,9],\"claimId\":\"claim-1\","
            + "\"dimension\":\"minecraft:the_nether\",\"facing\":\"north\",\"origin\":[-8,70,1000],\"terrainDigest\":\"td\"},"
            + "\"style\":{\"moodTags\":[\"a\",\"b\"],\"palette\":{\"wall\":\"minecraft:bricks\"}}}";
    /** SHA-256 of {@link #SMALL_PLAN_CANONICAL} in UTF-8, from a separate implementation. */
    private static final String SMALL_PLAN_HASH = "9601dddf0744ab174734f0ad239dd32d704cee9ba0e984d3f86beec049ecbf73";

    @Test
    void theCanonicalTextAndHashOfASmallPlanAreThePinnedOnes() {
        assertEquals(SMALL_PLAN_CANONICAL, CanonicalJson.write(PlanJson.contentTree(smallPlan())));
        assertEquals(SMALL_PLAN_HASH, smallPlan().contentHash());
    }

    @Test
    void changingAnySectionOfAPlanChangesTheHash() {
        SemanticPlan plan = loosePlan();
        Map<String, SemanticPlan> changed = new LinkedHashMap<>();
        changed.put("site", new SemanticPlan(plan.schemaVersion(), plan.planId(), plan.revision(), plan.parentRevision(),
                new Site("minecraft:overworld", new BuildFrame(new IntPos(100, 64, 200), Facing.WEST), plan.site().localBounds(), "", ""),
                plan.style(), plan.nodes(), plan.connections(), plan.logistics(), plan.provenance()));
        changed.put("style", new SemanticPlan(plan.schemaVersion(), plan.planId(), plan.revision(), plan.parentRevision(), plan.site(),
                new StyleSpec(Map.of("roof", "minecraft:bricks", "wall", "minecraft:stone_bricks"), Set.of("cozy")), plan.nodes(),
                plan.connections(), plan.logistics(), plan.provenance()));
        Connection first = plan.connections().get(0);
        List<Connection> editedConnections = new ArrayList<>(plan.connections());
        editedConnections.set(0, new Connection(first.id(), first.from(), first.to(), first.kind(), first.routing(),
                new Constraints(21, first.constraints().avoidNodeIds(), first.constraints().maxTurns(),
                        first.constraints().allowedEntryDirs())));
        changed.put("connection", new SemanticPlan(plan.schemaVersion(), plan.planId(), plan.revision(), plan.parentRevision(),
                plan.site(), plan.style(), plan.nodes(), editedConnections, plan.logistics(), plan.provenance()));
        changed.put("logistics", withLogistics(plan, new LogisticsPlan(plan.logistics().docks(), plan.logistics().routes(),
                List.of(new LogisticsPlan.CargoFlow("create:iron_sheet", 13.0, "dock-1", "dock-1")))));
        changed.put("schemaVersion", new SemanticPlan(plan.schemaVersion() + 1, plan.planId(), plan.revision(), plan.parentRevision(),
                plan.site(), plan.style(), plan.nodes(), plan.connections(), plan.logistics(), plan.provenance()));
        TreeMap<String, ParamValue> params = new TreeMap<>(plan.nodes().get(0).params());
        params.put("depth", new IntV(8));
        List<PlanNode> editedNodes = new ArrayList<>(plan.nodes());
        PlanNode structure = editedNodes.get(0);
        editedNodes.set(0, new PlanNode(structure.id(), structure.type(), structure.parent(), structure.anchor(), params,
                structure.tags(), structure.label()));
        changed.put("node param", new SemanticPlan(plan.schemaVersion(), plan.planId(), plan.revision(), plan.parentRevision(),
                plan.site(), plan.style(), editedNodes, plan.connections(), plan.logistics(), plan.provenance()));

        Map<String, String> seen = new HashMap<>();
        seen.put(plan.contentHash(), "the unchanged plan");
        for (Map.Entry<String, SemanticPlan> e : changed.entrySet()) {
            String clash = seen.put(e.getValue().contentHash(), e.getKey());
            assertNull(clash, "changing the " + e.getKey() + " must give a hash of its own, but it equals that of " + clash);
        }
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
        assertEquals("$: missing \"planId\"", missing.getMessage());

        String badPos = MiniJson.write(PlanJson.toTree(loosePlan())).replace("\"pos\":[0,0,0]", "\"pos\":[0,0]");
        PlanJsonException e = assertThrows(PlanJsonException.class, () -> PlanJson.planFromTree(MiniJson.parse(badPos)));
        assertEquals("$.nodes[0].anchor.pos: expected [u, v, w]", e.getMessage());

        String wrongVersion = MiniJson.write(PlanJson.toTree(loosePlan())).replace("\"schemaVersion\":1", "\"schemaVersion\":2");
        PlanJsonException v = assertThrows(PlanJsonException.class, () -> PlanJson.planFromTree(MiniJson.parse(wrongVersion)));
        assertEquals("$.schemaVersion: unsupported schemaVersion 2 (this build reads 1); the data is refused, not converted",
                v.getMessage());
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
        assertEquals("$.ops[0].op: unknown operation \"explode\"", e.getMessage());
    }
}
