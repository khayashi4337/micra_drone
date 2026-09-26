package io.github.khayashi4337.micradrone.build.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.ConnKind;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Dir6;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.LogisticsPlan;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanOp;
import io.github.khayashi4337.micradrone.build.model.PlanPatch;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.Routing;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.lang.MicraFunction;
import io.github.khayashi4337.micradrone.lang.MicraNone;
import io.github.khayashi4337.micradrone.lang.PlanAnchorArgs;
import io.github.khayashi4337.micradrone.lang.PlanRunLimits;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class PlanRecorderTest {
    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void operationsAreRecordedInCallOrder() {
        PlanRecorder r = new PlanRecorder();
        r.site("minecraft:overworld", 1, 2, 3, "east", new int[]{0, 0, 0, 5, 5, 5}, "", "");
        r.part("hut", "micra:structure", null, PlanAnchorArgs.absolute(0, 0, 0, 0, false), params("width", 7.0), List.of("a"), "label");
        r.updateParams("hut", params("depth", 9.0));
        r.relocate("hut", PlanAnchorArgs.absolute(1, 0, 1, 1, true));
        r.part("d", "micra:door", "hut", PlanAnchorArgs.surface("wall-n", "outer", 3, 0), params(), List.of(), "");
        r.removePart("d");
        PlanPatch patch = r.toPatch("p", 4, "script");
        assertEquals("p", patch.patchId());
        assertEquals(4, patch.baseRevision());
        assertEquals(6, patch.ops().size());
        assertTrue(patch.ops().get(0) instanceof PlanOp.SetSite);
        PlanOp.SetSite site = (PlanOp.SetSite) patch.ops().get(0);
        assertEquals(Facing.EAST, site.site().frame().facing());
        assertEquals(new Box(0, 0, 0, 5, 5, 5), site.site().localBounds());
        PlanOp.AddNode add = (PlanOp.AddNode) patch.ops().get(1);
        assertEquals(new ParamValue.IntV(7), add.node().params().get("width"));
        assertEquals(Set.of("a"), add.node().tags());
        assertEquals("label", add.node().label());
        PlanOp.MoveNode move = (PlanOp.MoveNode) patch.ops().get(3);
        assertEquals(new Anchor.Absolute(new LocalPos(1, 0, 1), new Rot(1, true)), move.anchor());
        PlanOp.AddNode door = (PlanOp.AddNode) patch.ops().get(4);
        assertEquals(new Anchor.OnSurface("wall-n", Side.OUTER, 3, 0), door.node().anchor());
    }

    @Test
    void styleAndMoodCallsMergeIntoOneSetStyleAtTheFirstCall() {
        PlanRecorder r = new PlanRecorder();
        r.style("roof", "minecraft:bricks");
        r.part("a", "micra:pillar", null, PlanAnchorArgs.absolute(0, 0, 0, 0, false), params(), List.of(), "");
        r.mood("cozy");
        r.style("wall", "minecraft:stone");
        PlanPatch patch = r.toPatch("p", 0, "s");
        assertEquals(2, patch.ops().size());
        PlanOp.SetStyle style = (PlanOp.SetStyle) patch.ops().get(0);
        assertEquals(Map.of("roof", "minecraft:bricks", "wall", "minecraft:stone"), style.style().palette());
        assertEquals(Set.of("cozy"), style.style().moodTags());
    }

    @Test
    void connectMapsViaAndConstraints() {
        PlanRecorder r = new PlanRecorder();
        r.connect("c1", "press-1.power_in", "shaft-2.out", "rotation", null, null);
        r.connect("c2", "a.out", "b.in", "item", List.of(), null);
        r.connect("c3", "a.out", "b.in", "item", List.of("s1"), params("max_length", 12.0, "avoid", List.of("x"), "max_turns", 2.0, "entry_dirs", List.of("up", "north")));
        PlanPatch patch = r.toPatch("p", 0, "s");
        Connection c1 = ((PlanOp.AddConnection) patch.ops().get(0)).connection();
        assertEquals("press-1", c1.from().nodeId());
        assertEquals("power_in", c1.from().port());
        assertEquals(ConnKind.ROTATION, c1.kind());
        assertEquals(Routing.AUTO, c1.routing());
        assertEquals(new Routing.Explicit(List.of()), ((PlanOp.AddConnection) patch.ops().get(1)).connection().routing());
        Connection c3 = ((PlanOp.AddConnection) patch.ops().get(2)).connection();
        assertEquals(new Routing.Explicit(List.of("s1")), c3.routing());
        assertEquals(12, c3.constraints().maxLength());
        assertEquals(2, c3.constraints().maxTurns());
        assertEquals(Set.of("x"), c3.constraints().avoidNodeIds());
        assertEquals(Set.of(Dir6.UP, Dir6.NORTH), c3.constraints().allowedEntryDirs());
    }

    @Test
    void logisticsIsReadFromDicts() {
        PlanRecorder r = new PlanRecorder();
        r.logistics(
                List.of(params("id", "dock-1", "pad", List.of(0.0, 0.0, 0.0, 8.0, 0.0, 8.0), "clearance", List.of(0.0, 1.0, 0.0, 8.0, 16.0, 8.0),
                        "approach", "north", "ports", List.of("press-1.item_out"), "connectors", List.of("conn-1"))),
                List.of(params("id", "route-1", "from", "dock-1", "to", "dock-1", "waypoints", List.of(List.of(0.0, 5.0, 0.0)), "airship", "mod:airship_a")),
                List.of(params("item", "create:iron_sheet", "per_min", 12.5, "from", "dock-1", "to", "dock-1")));
        PlanOp.SetLogistics l = (PlanOp.SetLogistics) r.toPatch("p", 0, "s").ops().get(0);
        assertEquals(1, l.logistics().docks().size());
        assertEquals(Facing.NORTH, l.logistics().docks().get(0).approach());
        assertEquals("press-1", l.logistics().docks().get(0).linkedPorts().get(0).nodeId());
        assertEquals(12.5, l.logistics().flows().get(0).perMin());
        assertEquals(new LocalPos(0, 5, 0), l.logistics().routes().get(0).waypoints().get(0));
        assertEquals("mod:airship_a", l.logistics().routes().get(0).airshipTemplateId());
    }

    @Test
    void badValuesAreIllegalArgumentsWithAMessage() {
        PlanRecorder r = new PlanRecorder();
        assertThrows(IllegalArgumentException.class, () -> r.site("d", 0, 0, 0, "up", new int[]{0, 0, 0, 1, 1, 1}, "", ""));
        assertThrows(IllegalArgumentException.class, () -> r.site("d", 0, 0, 0, "north", new int[]{5, 0, 0, 1, 1, 1}, "", ""));
        assertThrows(IllegalArgumentException.class, () -> r.part("a", "micra:pillar", null, PlanAnchorArgs.absolute(0, 0, 0, 0, false),
                params("k", params("nested", 1.0)), List.of(), ""));
        assertThrows(IllegalArgumentException.class, () -> r.connect("c", "nodot", "b.in", "item", null, null));
        assertThrows(IllegalArgumentException.class, () -> r.connect("c", "a.o", "b.i", "steam", null, null));
        assertThrows(IllegalArgumentException.class, () -> r.connect("c", "a.o", "b.i", "item", null, params("bogus", 1.0)));
        assertThrows(IllegalArgumentException.class, () -> r.part("a", "micra:pillar", null, PlanAnchorArgs.surface("w", "top", 0, 0), params(), List.of(), ""));
    }

    @Test
    void nonDataValuesAreRejectedWithTheArgumentNamed() {
        PlanRecorder r = new PlanRecorder();
        // a function, None and a set are not data the plan can hold - the message names the offending argument
        IllegalArgumentException fn = assertThrows(IllegalArgumentException.class, () -> r.part("a", "micra:pillar", null,
                PlanAnchorArgs.absolute(0, 0, 0, 0, false), params("cb", new MicraFunction("f", List.of(), List.of())), List.of(), ""));
        assertTrue(fn.getMessage().contains("cb"), fn.getMessage());
        assertTrue(fn.getMessage().contains("params"), fn.getMessage());
        IllegalArgumentException none = assertThrows(IllegalArgumentException.class, () -> r.part("a", "micra:pillar", null,
                PlanAnchorArgs.absolute(0, 0, 0, 0, false), params("x", MicraNone.INSTANCE), List.of(), ""));
        assertTrue(none.getMessage().contains("x"), none.getMessage());
        IllegalArgumentException set = assertThrows(IllegalArgumentException.class, () -> r.updateParams("a",
                params("s", Set.of(1.0))));
        assertTrue(set.getMessage().contains("s"), set.getMessage());
        // the same rule inside the logistics lists
        assertThrows(IllegalArgumentException.class, () -> r.logistics(List.of(MicraNone.INSTANCE), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> r.logistics(List.of(params("id", "d", "pad", Set.of(1.0))), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> r.connect("c", "a.o", "b.i", "item", null, params("avoid", Set.of("x"))));
    }

    @Test
    void mutatingThePassedListsAfterTheCallDoesNotChangeTheRecordedPatch() {
        PlanRecorder r = new PlanRecorder();
        Map<String, Object> dock = params("id", "dock-1", "pad", new ArrayList<>(List.of(0.0, 0.0, 0.0, 8.0, 0.0, 8.0)),
                "clearance", List.of(0.0, 0.0, 0.0, 8.0, 16.0, 8.0), "approach", "north");
        List<Object> docks = new ArrayList<>(List.of(dock));
        List<String> via = new ArrayList<>(List.of("s1"));
        List<Object> xs = new ArrayList<>(List.of(1.0, 2.0));
        r.logistics(docks, List.of(), List.of());
        r.connect("c1", "a.out", "b.in", "item", via, null);
        r.part("p", "micra:pillar", null, PlanAnchorArgs.absolute(0, 0, 0, 0, false), params("pts", xs), List.of(), "");
        // the script is free to keep mutating its own lists and dicts afterwards
        docks.add(params("id", "late-dock"));
        dock.put("id", "mutated");
        via.add("s2");
        xs.add(3.0);
        PlanPatch patch = r.toPatch("p", 0, "s");
        PlanOp.SetLogistics l = (PlanOp.SetLogistics) patch.ops().get(0);
        assertEquals(1, l.logistics().docks().size());
        assertEquals("dock-1", l.logistics().docks().get(0).id());
        PlanOp.AddConnection c = (PlanOp.AddConnection) patch.ops().get(1);
        assertEquals(new Routing.Explicit(List.of("s1")), c.connection().routing());
        PlanOp.AddNode node = (PlanOp.AddNode) patch.ops().get(2);
        assertEquals(new ParamValue.ListV(List.of(new ParamValue.IntV(1), new ParamValue.IntV(2))), node.node().params().get("pts"));
    }

    @Test
    void thePatchCannotHoldNullOps() {
        // a style call with no following ops must still produce a clean patch, not a null slot
        PlanRecorder r = new PlanRecorder();
        r.style("roof", "minecraft:bricks");
        PlanPatch patch = r.toPatch("p", 0, "s");
        assertEquals(1, patch.ops().size());
        assertEquals(Map.of("roof", "minecraft:bricks"), ((PlanOp.SetStyle) patch.ops().get(0)).style().palette());
    }

    // ---- strictness of the logistics dicts and the cases the first round missed (fix round 1) ----

    @Test
    void unknownKeysInTheLogisticsDictsAreRejectedWithTheAllowedKeysNamed() {
        PlanRecorder r = new PlanRecorder();
        List<Object> box = List.of(0.0, 0.0, 0.0, 8.0, 0.0, 8.0);
        IllegalArgumentException dock = assertThrows(IllegalArgumentException.class, () -> r.logistics(
                List.of(params("id", "d", "pad", box, "clearance", box, "approach", "north", "port", List.of("a.b"))),
                List.of(), List.of()));
        // the offending key is quoted (「port」), not a bare substring of the allowed "ports"
        assertTrue(dock.getMessage().contains("「port」"), dock.getMessage());
        assertTrue(dock.getMessage().contains("ports"), dock.getMessage());
        IllegalArgumentException route = assertThrows(IllegalArgumentException.class, () -> r.logistics(List.of(),
                List.of(params("id", "r", "from", "a", "to", "b", "waypoint", List.of())), List.of()));
        assertTrue(route.getMessage().contains("「waypoint」"), route.getMessage());
        assertTrue(route.getMessage().contains("waypoints"), route.getMessage());
        IllegalArgumentException flow = assertThrows(IllegalArgumentException.class, () -> r.logistics(List.of(),
                List.of(), List.of(params("item", "i", "per_min", 1.0, "from", "a", "to", "b", "rate", 2.0))));
        assertTrue(flow.getMessage().contains("rate"), flow.getMessage());
        assertTrue(flow.getMessage().contains("per_min"), flow.getMessage());
    }

    @Test
    void disconnectIsRecordedAsARemoveConnection() {
        PlanRecorder r = new PlanRecorder();
        r.disconnect("c-1");
        assertEquals(new PlanOp.RemoveConnection("c-1"), r.toPatch("p", 0, "s").ops().get(0));
    }

    @Test
    void aNoneAirshipIsRecordedAsNull() {
        PlanRecorder r = new PlanRecorder();
        r.logistics(List.of(), List.of(params("id", "r", "from", "a", "to", "b", "airship", MicraNone.INSTANCE)), List.of());
        LogisticsPlan.Route route = ((PlanOp.SetLogistics) r.toPatch("p", 0, "s").ops().get(0)).logistics().routes().get(0);
        assertNull(route.airshipTemplateId());
        // a missing key means the same
        PlanRecorder r2 = new PlanRecorder();
        r2.logistics(List.of(), List.of(params("id", "r", "from", "a", "to", "b")), List.of());
        assertNull(((PlanOp.SetLogistics) r2.toPatch("p", 0, "s").ops().get(0)).logistics().routes().get(0).airshipTemplateId());
    }

    @Test
    void aNonNumberPerMinAndAWaypointThatIsNotThreeNumbersAreRejected() {
        PlanRecorder r = new PlanRecorder();
        assertThrows(IllegalArgumentException.class, () -> r.logistics(List.of(), List.of(),
                List.of(params("item", "i", "per_min", "fast", "from", "a", "to", "b"))));
        assertThrows(IllegalArgumentException.class, () -> r.logistics(List.of(),
                List.of(params("id", "r", "from", "a", "to", "b", "waypoints", List.of(List.of(0.0, 5.0)))), List.of()));
        assertThrows(IllegalArgumentException.class, () -> r.logistics(List.of(),
                List.of(params("id", "r", "from", "a", "to", "b", "waypoints", List.of(List.of(0.0, "x", 0.0)))), List.of()));
    }

    @Test
    void aMoodAloneRecordsAsOneSetStyle() {
        PlanRecorder r = new PlanRecorder();
        r.mood("cozy");
        PlanPatch patch = r.toPatch("p", 0, "s");
        assertEquals(1, patch.ops().size());
        PlanOp.SetStyle style = (PlanOp.SetStyle) patch.ops().get(0);
        assertEquals(Set.of("cozy"), style.style().moodTags());
        assertTrue(style.style().palette().isEmpty());
    }

    // ---- run-wide budgets on what the recorder retains (fix round 3) ----

    @Test
    void recordedParamCopiesCountAgainstTheRunWideElementBudget() {
        // one part call with a 9,000-element list under one key costs 1 (the op) + 9,001 nodes
        // (the list itself counts one, every scalar counts one - the same walk the params copy
        // does) + 0 tags = 9,002, so 22 calls charge 198,044 of the 200,000-element budget and
        // the 23rd is refused
        PlanRecorder r = new PlanRecorder();
        List<Object> big = new ArrayList<>();
        for (int i = 0; i < 9_000; i++) {
            big.add((double) i);
        }
        Map<String, Object> params = params("x", big);
        for (int i = 0; i < 22; i++) {
            r.part("p" + i, "micra:pillar", null, PlanAnchorArgs.absolute(0, 0, 0, 0, false), params, List.of(), "");
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> r.part("p22",
                "micra:pillar", null, PlanAnchorArgs.absolute(0, 0, 0, 0, false), params, List.of(), ""));
        assertTrue(e.getMessage().contains("200000"), e.getMessage());
    }

    @Test
    void recordedTagsCountAgainstTheRunWideElementBudget() {
        // a part call with 30,000 distinct tags costs 1 + 0 params + 30,000 = 30,001, so 6 calls
        // fit (180,006) and the 7th is refused
        PlanRecorder r = new PlanRecorder();
        List<String> tags = new ArrayList<>();
        for (int i = 0; i < 30_000; i++) {
            tags.add("t" + i);
        }
        for (int i = 0; i < 6; i++) {
            r.part("p" + i, "micra:pillar", null, PlanAnchorArgs.absolute(0, 0, 0, 0, false), params(), tags, "");
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> r.part("p6",
                "micra:pillar", null, PlanAnchorArgs.absolute(0, 0, 0, 0, false), params(), tags, ""));
        assertTrue(e.getMessage().contains("200000"), e.getMessage());
    }

    @Test
    void aRecordedViaListCountsAgainstTheRunWideElementBudget() {
        // connect with a 30,000-entry via list costs 1 + 30,000 = 30,001 - same 6/7 boundary
        PlanRecorder r = new PlanRecorder();
        List<String> via = new ArrayList<>();
        for (int i = 0; i < 30_000; i++) {
            via.add("n" + i);
        }
        for (int i = 0; i < 6; i++) {
            r.connect("c" + i, "a.out", "b.in", "item", via, null);
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> r.connect("c6", "a.out", "b.in", "item", via, null));
        assertTrue(e.getMessage().contains("200000"), e.getMessage());
    }

    @Test
    void aSquaredLogisticsCallIsRefusedBeforeItsListsAreBuilt() {
        // 19,000 references to ONE dock whose ports list holds 19,000 references: reading it the
        // old way materialised 19,000 x 19,000 = 361,000,000 PortRef objects in one call. Each
        // dock is charged 1 (itself) + 19,000 (ports) + 0 (connectors) = 19,001 BEFORE its PortRef
        // list is built, and the call itself costs 1 - so 10 docks fit (1 + 190,010 = 190,011)
        // and the 11th is refused while being processed, long before any product is built
        List<Object> box = List.of(0.0, 0.0, 0.0, 8.0, 0.0, 8.0);
        List<Object> ports = new ArrayList<>();
        for (int i = 0; i < 19_000; i++) {
            ports.add("n.p");
        }
        Map<String, Object> dock = params("id", "d", "pad", box, "clearance", box, "approach", "north", "ports", ports);
        List<Object> tenDocks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tenDocks.add(dock);
        }
        PlanRecorder fits = new PlanRecorder();
        fits.logistics(tenDocks, List.of(), List.of());
        assertEquals(10, ((PlanOp.SetLogistics) fits.toPatch("p", 0, "s").ops().get(0)).logistics().docks().size());
        PlanRecorder r = new PlanRecorder();
        List<Object> manyDocks = new ArrayList<>();
        for (int i = 0; i < 19_000; i++) {
            manyDocks.add(dock);
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> r.logistics(manyDocks, List.of(), List.of()));
        assertTrue(e.getMessage().contains("200000"), e.getMessage());
    }

    @Test
    void printedTextCountsAgainstTheRunWideCharacterBudget() {
        // the printed list retains every rendered string for the whole run: two 524,288-character
        // prints would retain 1,048,576 characters, so the second is refused before it is kept
        PlanRecorder r = new PlanRecorder();
        String text = "x".repeat(524_288);
        r.print(text);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> r.print(text));
        assertTrue(e.getMessage().contains("1000000"), e.getMessage());
        assertEquals(1, r.printed().size(), "a refused print is not retained");
        // exactly 1,000,000 characters in total still fit (the check is ">", not ">="); one more is refused
        PlanRecorder exact = new PlanRecorder();
        exact.print("x".repeat(1_000_000));
        assertThrows(IllegalArgumentException.class, () -> exact.print("x"));
    }

    @Test
    void theElementBudgetIsCumulativeAcrossTheScriptsOfOneRun() {
        // one PlanRecorder serves every script of a run: script 1 records 22 part calls of the
        // 9,000-element params list (22 x 9,002 = 198,044) and script 2's single call of the same
        // shape pushes the total to 207,046 - refused as an ordinary E-SCHEMA issue, with no patch
        // at all (each script gets its own interpreter, so script 2 builds its own list)
        StringBuilder script1 = new StringBuilder("a = []\nfor i in range(9000):\n    a.append(i)\n");
        for (int i = 0; i < 22; i++) {
            script1.append("wall(\"w").append(i).append("\", None, [0,0,0], {\"x\": a})\n");
        }
        String script2 = "b = []\nfor i in range(9000):\n    b.append(i)\nwall(\"w\", None, [0,0,0], {\"x\": b})\n";
        PlanScriptRunner.Result r = PlanScriptRunner.run(
                List.of(script1.toString(), script2), "p", 0, "t", PlanRunLimits.DEFAULT);
        assertNull(r.patch());
        assertEquals(1, r.issues().size());
        assertEquals("E-SCHEMA:#run:2", r.issues().get(0).id());
        assertTrue(r.issues().get(0).message().contains("200000"), r.issues().get(0).message());
    }

    // ---- the remaining charges of the run-wide element budget (fix round 6) ----

    @Test
    void dockConnectorsCountAgainstTheRunWideElementBudget() {
        // a dock with an empty ports list and 19,000 connector ids costs 1 (itself) + 0 (ports)
        // + 19,000 (connectors) = 19,001; with the 1 for the SetLogistics op itself ten docks in
        // one call charge 1 + 10 x 19,001 = 190,011 of the 200,000-element budget, and the 11th
        // dock of a longer call is refused while it is processed (190,011 + 19,001 = 209,012)
        List<Object> box = List.of(0.0, 0.0, 0.0, 8.0, 0.0, 8.0);
        List<Object> connectors = new ArrayList<>();
        for (int i = 0; i < 19_000; i++) {
            connectors.add("conn-1");
        }
        Map<String, Object> dock = params("id", "d", "pad", box, "clearance", box, "approach", "north",
                "ports", List.of(), "connectors", connectors);
        List<Object> tenDocks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tenDocks.add(dock);
        }
        PlanRecorder fits = new PlanRecorder();
        fits.logistics(tenDocks, List.of(), List.of());
        assertEquals(10, ((PlanOp.SetLogistics) fits.toPatch("p", 0, "s").ops().get(0)).logistics().docks().size());
        PlanRecorder r = new PlanRecorder();
        List<Object> elevenDocks = new ArrayList<>(tenDocks);
        elevenDocks.add(dock);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> r.logistics(elevenDocks, List.of(), List.of()));
        assertTrue(e.getMessage().contains("200000"), e.getMessage());
    }

    @Test
    void routeWaypointsCountAgainstTheRunWideElementBudget() {
        // a route with 19,000 waypoints and no docks costs 1 (itself) + 19,000 = 19,001, so ten
        // routes in one call charge 1 + 10 x 19,001 = 190,011 and the 11th is refused (209,012)
        // - the same boundary as the docks; from/to are plain ids, the recorder does not check
        // that the docks they name exist
        List<Object> origin = List.of(0.0, 0.0, 0.0);
        List<Object> waypoints = new ArrayList<>();
        for (int i = 0; i < 19_000; i++) {
            waypoints.add(origin);
        }
        Map<String, Object> route = params("id", "r", "from", "a", "to", "b", "waypoints", waypoints);
        List<Object> tenRoutes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tenRoutes.add(route);
        }
        PlanRecorder fits = new PlanRecorder();
        fits.logistics(List.of(), tenRoutes, List.of());
        assertEquals(10, ((PlanOp.SetLogistics) fits.toPatch("p", 0, "s").ops().get(0)).logistics().routes().size());
        PlanRecorder r = new PlanRecorder();
        List<Object> elevenRoutes = new ArrayList<>(tenRoutes);
        elevenRoutes.add(route);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> r.logistics(List.of(), elevenRoutes, List.of()));
        assertTrue(e.getMessage().contains("200000"), e.getMessage());
    }

    @Test
    void constraintAvoidIdsCountAgainstTheRunWideElementBudget() {
        // connect with constraints {"avoid": 30,000 ids} and via null costs 1 (the op) + 0 (via)
        // + 30,000 (avoid) = 30,001, so 6 calls fit (180,006) and the 7th is refused while its
        // constraints are read (180,006 + 1 + 30,000 = 210,007)
        PlanRecorder r = new PlanRecorder();
        List<String> avoid = new ArrayList<>();
        for (int i = 0; i < 30_000; i++) {
            avoid.add("n" + i);
        }
        Map<String, Object> constraints = params("avoid", avoid);
        for (int i = 0; i < 6; i++) {
            r.connect("c" + i, "a.out", "b.in", "item", null, constraints);
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> r.connect("c6", "a.out", "b.in", "item", null, constraints));
        assertTrue(e.getMessage().contains("200000"), e.getMessage());
    }

    @Test
    void constraintEntryDirsCountAgainstTheRunWideElementBudget() {
        // the same charge applies to entry_dirs: {"entry_dirs": 30,000 copies of "north"} costs
        // 30,000 even though the recorded set collapses to one Dir6 - the charge is on the list
        // that was read, not on the deduplicated result - so again 6 calls fit and the 7th is
        // refused (180,006, then 210,007)
        PlanRecorder r = new PlanRecorder();
        List<String> dirs = new ArrayList<>();
        for (int i = 0; i < 30_000; i++) {
            dirs.add("north");
        }
        Map<String, Object> constraints = params("entry_dirs", dirs);
        for (int i = 0; i < 6; i++) {
            r.connect("c" + i, "a.out", "b.in", "item", null, constraints);
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> r.connect("c6", "a.out", "b.in", "item", null, constraints));
        assertTrue(e.getMessage().contains("200000"), e.getMessage());
    }

    @Test
    void recordedFlowsCountAgainstTheRunWideElementBudget() {
        // each flow costs 1 recorded element: with the 1 for the SetLogistics op a call carrying
        // 199,999 flows charges exactly 1 + 199,999 = 200,000 (the check is ">", so exactly the
        // budget still fits) and a call carrying 200,000 is refused at the last flow
        Map<String, Object> flow = params("item", "i", "per_min", 1.0, "from", "a", "to", "b");
        List<Object> maxFlows = new ArrayList<>();
        for (int i = 0; i < 199_999; i++) {
            maxFlows.add(flow);
        }
        PlanRecorder fits = new PlanRecorder();
        fits.logistics(List.of(), List.of(), maxFlows);
        assertEquals(199_999, ((PlanOp.SetLogistics) fits.toPatch("p", 0, "s").ops().get(0)).logistics().flows().size());
        PlanRecorder r = new PlanRecorder();
        List<Object> tooMany = new ArrayList<>(maxFlows);
        tooMany.add(flow);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> r.logistics(List.of(), List.of(), tooMany));
        assertTrue(e.getMessage().contains("200000"), e.getMessage());
    }

    @Test
    void theLogisticsOpItselfCountsAgainstTheRunWideElementBudget() {
        // logistics([], [], []) records one SetLogistics op and so costs 1: 200,000 calls exactly
        // fill the budget and the 200,001st is refused
        PlanRecorder r = new PlanRecorder();
        for (int i = 0; i < 200_000; i++) {
            r.logistics(List.of(), List.of(), List.of());
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> r.logistics(List.of(), List.of(), List.of()));
        assertTrue(e.getMessage().contains("200000"), e.getMessage());
    }

    @Test
    void styleAndMoodCallsCountAgainstTheRunWideElementBudget() {
        // a style call costs 1 even though it only fills the shared palette: 200,000 calls fit
        // and the 200,001st is refused; a mood call costs the same on a fresh recorder
        PlanRecorder r = new PlanRecorder();
        for (int i = 0; i < 200_000; i++) {
            r.style("r", "m");
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> r.style("r", "m"));
        assertTrue(e.getMessage().contains("200000"), e.getMessage());
        PlanRecorder moods = new PlanRecorder();
        for (int i = 0; i < 200_000; i++) {
            moods.mood("t");
        }
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class, () -> moods.mood("t"));
        assertTrue(e2.getMessage().contains("200000"), e2.getMessage());
    }

    @Test
    void everyOtherOperationCostsExactlyOneRecordedElement() {
        // each of these calls costs exactly 1 element (empty params walk no nodes, empty tags and
        // a null via add nothing), so 200,000 calls exactly fill the 200,000-element budget and
        // the 200,001st is refused - removing the chargeRecordedElements(1) of any one of them
        // lets all 200,001 calls through and fails its case
        Map<String, Consumer<PlanRecorder>> cases = new LinkedHashMap<>();
        cases.put("site", r -> r.site("minecraft:overworld", 0, 0, 0, "north", new int[]{0, 0, 0, 1, 1, 1}, "", ""));
        cases.put("part", r -> r.part("p", "micra:pillar", null, PlanAnchorArgs.absolute(0, 0, 0, 0, false),
                params(), List.of(), ""));
        cases.put("updateParams", r -> r.updateParams("p", params()));
        cases.put("relocate", r -> r.relocate("p", PlanAnchorArgs.absolute(0, 0, 0, 0, false)));
        cases.put("removePart", r -> r.removePart("p"));
        cases.put("connect", r -> r.connect("c", "a.out", "b.in", "item", null, null));
        cases.put("disconnect", r -> r.disconnect("c"));
        for (Map.Entry<String, Consumer<PlanRecorder>> testCase : cases.entrySet()) {
            PlanRecorder r = new PlanRecorder();
            for (int i = 0; i < 200_000; i++) {
                testCase.getValue().accept(r);
            }
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> testCase.getValue().accept(r), testCase.getKey());
            assertTrue(e.getMessage().contains("200000"), testCase.getKey() + ": " + e.getMessage());
        }
    }
}
