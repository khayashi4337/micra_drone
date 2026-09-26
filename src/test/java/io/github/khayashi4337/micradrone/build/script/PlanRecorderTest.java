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
import io.github.khayashi4337.micradrone.lang.PlanBudgetException;
import io.github.khayashi4337.micradrone.lang.PlanRunLimits;
import java.util.ArrayList;
import java.util.Collections;
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
        // a part call with 30,000 tags costs 1 + 0 params + 30,000 = 30,001, so 6 calls
        // fit (180,006) and the 7th is refused. The tags are one-character strings on purpose:
        // the recorded characters budget (1,000,000 over the run) must not fire before the
        // element budget this test probes - 6 calls x ~30,013 characters stay far under it
        PlanRecorder r = new PlanRecorder();
        List<String> tags = new ArrayList<>();
        for (int i = 0; i < 30_000; i++) {
            tags.add("t");
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
        // connect with a 30,000-entry via list costs 1 + 30,000 = 30,001 - same 6/7 boundary.
        // The ids are one character each so the 6 calls stay under the run-wide character
        // budget and the element budget is what refuses the 7th
        PlanRecorder r = new PlanRecorder();
        List<String> via = new ArrayList<>();
        for (int i = 0; i < 30_000; i++) {
            via.add("n");
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
        // shape pushes the total to 207,046 - a budget refusal, reported as an E-SCRIPT-LIMIT
        // issue with no patch at all (each script gets its own interpreter, so script 2 builds
        // its own list)
        StringBuilder script1 = new StringBuilder("a = []\nfor i in range(9000):\n    a.append(i)\n");
        for (int i = 0; i < 22; i++) {
            script1.append("wall(\"w").append(i).append("\", None, [0,0,0], {\"x\": a})\n");
        }
        String script2 = "b = []\nfor i in range(9000):\n    b.append(i)\nwall(\"w\", None, [0,0,0], {\"x\": b})\n";
        PlanScriptRunner.Result r = PlanScriptRunner.run(
                List.of(script1.toString(), script2), "p", 0, "t", PlanRunLimits.DEFAULT);
        assertNull(r.patch());
        assertEquals(1, r.issues().size());
        assertEquals("E-SCRIPT-LIMIT:#run:2", r.issues().get(0).id());
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
        // (one-character connector ids: ten docks must stay under the 1,000,000-character
        // budget - 10 x 19,006 chars - so the element budget remains what refuses the 11th)
        List<Object> connectors = new ArrayList<>();
        for (int i = 0; i < 19_000; i++) {
            connectors.add("c");
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
        // one-character ids keep 6 calls under the character budget (~180,090 chars) so the
        // element budget fires first
        for (int i = 0; i < 30_000; i++) {
            avoid.add("n");
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
        // a null via add nothing). After 199,999 filler ops the first call brings the total to
        // exactly 200,000 - accepted, the check is ">" - and the second is refused. Removing the
        // call's own chargeRecordedElements(1) leaves 199,999 for both calls and fails the case.
        // The filler is removePart(""), which records zero characters: most calls below also
        // carry a few characters of id/kind text, and 200,000 such calls would trip the separate
        // 1,000,000-character budget long before the element budget this test probes.
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
            for (int i = 0; i < 199_999; i++) {
                r.removePart("");
            }
            testCase.getValue().accept(r);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> testCase.getValue().accept(r), testCase.getKey());
            assertTrue(e.getMessage().contains("200000"), testCase.getKey() + ": " + e.getMessage());
        }
    }

    // ---- run-wide budget on the characters the recorder retains (fix round 7) ----

    /** One shared 300,000-character string: three calls carrying it stay under 1,000,000. */
    private static final String BIG_RECORDED_TEXT = "x".repeat(300_000);
    private static final PlanAnchorArgs ABSOLUTE_ANCHOR = PlanAnchorArgs.absolute(0, 0, 0, 0, false);
    private static final List<Object> BOX6 = List.of(0.0, 0.0, 0.0, 8.0, 0.0, 8.0);

    @Test
    void everyRecordedStringCountsItsLengthAgainstTheRunWideCharacterBudget() {
        // one case per string site the recorder keeps (the javadoc's cost rule): the
        // 300,000-character string sits in exactly that argument and the rest are a few
        // characters each, so a call costs ~300,0xx recorded characters - three calls fit the
        // 1,000,000 budget (at most ~900,0xx) and the fourth is refused with the limit named.
        // Removing the charge of the probed site leaves only the small strings, the fourth
        // call goes through and the case fails. Sites that are enum-parsed before recording
        // (site facing, anchor side, connect kind, entry_dirs names, dock approach) cannot
        // carry a long string - the parse rejects it - so they have no case here.
        Map<String, Consumer<PlanRecorder>> cases = new LinkedHashMap<>();
        cases.put("site dimension", r -> r.site(BIG_RECORDED_TEXT, 0, 0, 0, "north", new int[]{0, 0, 0, 1, 1, 1}, "", ""));
        cases.put("site terrainDigest", r -> r.site("d", 0, 0, 0, "north", new int[]{0, 0, 0, 1, 1, 1}, BIG_RECORDED_TEXT, ""));
        cases.put("site claimId", r -> r.site("d", 0, 0, 0, "north", new int[]{0, 0, 0, 1, 1, 1}, "", BIG_RECORDED_TEXT));
        cases.put("style role", r -> r.style(BIG_RECORDED_TEXT, "m"));
        cases.put("style material", r -> r.style("r", BIG_RECORDED_TEXT));
        cases.put("mood tag", r -> r.mood(BIG_RECORDED_TEXT));
        cases.put("part id", r -> r.part(BIG_RECORDED_TEXT, "t", null, ABSOLUTE_ANCHOR, params(), List.of(), ""));
        cases.put("part type", r -> r.part("p", BIG_RECORDED_TEXT, null, ABSOLUTE_ANCHOR, params(), List.of(), ""));
        cases.put("part parent", r -> r.part("p", "t", BIG_RECORDED_TEXT, ABSOLUTE_ANCHOR, params(), List.of(), ""));
        cases.put("part label", r -> r.part("p", "t", null, ABSOLUTE_ANCHOR, params(), List.of(), BIG_RECORDED_TEXT));
        cases.put("part tag", r -> r.part("p", "t", null, ABSOLUTE_ANCHOR, params(), List.of(BIG_RECORDED_TEXT), ""));
        cases.put("part params key", r -> r.part("p", "t", null, ABSOLUTE_ANCHOR, params(BIG_RECORDED_TEXT, 1.0), List.of(), ""));
        cases.put("part params value", r -> r.part("p", "t", null, ABSOLUTE_ANCHOR, params("k", BIG_RECORDED_TEXT), List.of(), ""));
        cases.put("part params nested value",
                r -> r.part("p", "t", null, ABSOLUTE_ANCHOR, params("k", List.of(BIG_RECORDED_TEXT)), List.of(), ""));
        // the anchor's retained texts: a surface target lands in OnSurface.nodeId and a slot
        // name in InSlot.slotId (charged together with the parsed-away side text)
        cases.put("part surface target", r -> r.part("p", "t", null,
                PlanAnchorArgs.surface(BIG_RECORDED_TEXT, "outer", 0, 0), params(), List.of(), ""));
        cases.put("part slot id", r -> r.part("p", "t", null,
                PlanAnchorArgs.slot(BIG_RECORDED_TEXT, 0, false), params(), List.of(), ""));
        cases.put("relocate surface target",
                r -> r.relocate("p", PlanAnchorArgs.surface(BIG_RECORDED_TEXT, "outer", 0, 0)));
        cases.put("relocate slot id", r -> r.relocate("p", PlanAnchorArgs.slot(BIG_RECORDED_TEXT, 0, false)));
        cases.put("updateParams id", r -> r.updateParams(BIG_RECORDED_TEXT, params()));
        cases.put("updateParams value", r -> r.updateParams("p", params("k", BIG_RECORDED_TEXT)));
        cases.put("relocate id", r -> r.relocate(BIG_RECORDED_TEXT, ABSOLUTE_ANCHOR));
        cases.put("removePart id", r -> r.removePart(BIG_RECORDED_TEXT));
        cases.put("disconnect id", r -> r.disconnect(BIG_RECORDED_TEXT));
        cases.put("connect id", r -> r.connect(BIG_RECORDED_TEXT, "a.b", "c.d", "item", null, null));
        cases.put("connect from", r -> r.connect("c", "n." + BIG_RECORDED_TEXT, "c.d", "item", null, null));
        cases.put("connect to", r -> r.connect("c", "a.b", "n." + BIG_RECORDED_TEXT, "item", null, null));
        cases.put("connect via id", r -> r.connect("c", "a.b", "c.d", "item", List.of(BIG_RECORDED_TEXT), null));
        cases.put("connect avoid id",
                r -> r.connect("c", "a.b", "c.d", "item", null, params("avoid", List.of(BIG_RECORDED_TEXT))));
        cases.put("dock id", r -> r.logistics(List.of(
                params("id", BIG_RECORDED_TEXT, "pad", BOX6, "clearance", BOX6, "approach", "north")), List.of(), List.of()));
        cases.put("dock port text", r -> r.logistics(List.of(params("id", "d", "pad", BOX6, "clearance", BOX6,
                "approach", "north", "ports", List.of("n." + BIG_RECORDED_TEXT))), List.of(), List.of()));
        cases.put("dock connector", r -> r.logistics(List.of(params("id", "d", "pad", BOX6, "clearance", BOX6,
                "approach", "north", "connectors", List.of(BIG_RECORDED_TEXT))), List.of(), List.of()));
        cases.put("route id", r -> r.logistics(List.of(), List.of(params("id", BIG_RECORDED_TEXT, "from", "a", "to", "b")), List.of()));
        cases.put("route from", r -> r.logistics(List.of(), List.of(params("id", "r", "from", BIG_RECORDED_TEXT, "to", "b")), List.of()));
        cases.put("route to", r -> r.logistics(List.of(), List.of(params("id", "r", "from", "a", "to", BIG_RECORDED_TEXT)), List.of()));
        cases.put("route airship", r -> r.logistics(List.of(),
                List.of(params("id", "r", "from", "a", "to", "b", "airship", BIG_RECORDED_TEXT)), List.of()));
        cases.put("flow item", r -> r.logistics(List.of(), List.of(),
                List.of(params("item", BIG_RECORDED_TEXT, "per_min", 1.0, "from", "a", "to", "b"))));
        cases.put("flow from", r -> r.logistics(List.of(), List.of(),
                List.of(params("item", "i", "per_min", 1.0, "from", BIG_RECORDED_TEXT, "to", "b"))));
        cases.put("flow to", r -> r.logistics(List.of(), List.of(),
                List.of(params("item", "i", "per_min", 1.0, "from", "a", "to", BIG_RECORDED_TEXT))));
        for (Map.Entry<String, Consumer<PlanRecorder>> testCase : cases.entrySet()) {
            PlanRecorder r = new PlanRecorder();
            testCase.getValue().accept(r);
            testCase.getValue().accept(r);
            testCase.getValue().accept(r);
            PlanBudgetException e = assertThrows(PlanBudgetException.class,
                    () -> testCase.getValue().accept(r), testCase.getKey());
            assertTrue(e.getMessage().contains("1000000"), testCase.getKey() + ": " + e.getMessage());
        }
    }

    @Test
    void theRecordedCharacterBudgetIsCumulativeAcrossTheScriptsOfOneRun() {
        // the per-script allocation budget (10,000,000 units) resets for every script and cannot
        // see what earlier scripts handed the recorder. Each script doubles "ā" 19 times into a
        // 524,288-character string - 524,288 characters charged, far under the per-script
        // budget - and calls remove_part(x) once: script 1 is accepted (524,288 <= 1,000,000),
        // script 2's call is refused (1,048,576 > 1,000,000) as E-SCRIPT-LIMIT naming script 2,
        // and scripts 3-8 are refused the same way. No OutOfMemoryError: the run returns issues.
        String script = "x = \"ā\"\nfor i in range(19):\n    x = x + x\nremove_part(x)\n";
        assertTrue(script.length() < PlanScriptWriter.MAX_SCRIPT_CHARS,
                "the script must stay far under the per-script length limit");
        PlanScriptRunner.Result r = PlanScriptRunner.run(
                Collections.nCopies(8, script), "p", 0, "t", PlanRunLimits.DEFAULT);
        assertNull(r.patch());
        assertEquals(7, r.issues().size(), r.issues().toString());
        assertEquals("E-SCRIPT-LIMIT:#run:2", r.issues().get(0).id());
        assertTrue(r.issues().get(0).message().contains("1000000"), r.issues().get(0).message());
        for (int i = 0; i < r.issues().size(); i++) {
            assertEquals("E-SCRIPT-LIMIT:#run:" + (i + 2), r.issues().get(i).id());
        }
    }

    @Test
    void theControllersStringAccumulationReproEndsAsLimitIssues() {
        // the controller's reproduction verbatim: each of the 8 scripts hands the recorder up
        // to 16 fresh ~524,289-character strings (x + str(k)) - about 16 MB of retained text per
        // script against 16 counted elements, which OutOfMemoryError'd at -Xmx256m before the
        // recorded-characters budget existed. Now each script's second remove_part is refused
        // (524,289 + 524,289 > 1,000,000) and every script ends as an E-SCRIPT-LIMIT issue.
        String script = "x = \"ā\"\nfor i in range(19):\n    x = x + x\n"
                + "for k in range(16):\n    remove_part(x + str(k))\n";
        assertTrue(script.length() < PlanScriptWriter.MAX_SCRIPT_CHARS,
                "the script must stay far under the per-script length limit");
        PlanScriptRunner.Result r = PlanScriptRunner.run(
                Collections.nCopies(8, script), "p", 0, "t", PlanRunLimits.DEFAULT);
        assertNull(r.patch());
        assertEquals(8, r.issues().size(), r.issues().toString());
        for (int i = 0; i < r.issues().size(); i++) {
            assertEquals("E-SCRIPT-LIMIT:#run:" + (i + 1), r.issues().get(i).id());
            assertTrue(r.issues().get(i).message().contains("1000000"), r.issues().get(i).message());
        }
    }

    @Test
    void aRefusedPrintDoesNotCountItsCharacters() {
        // a print refused by the output budget retains nothing, so its characters must not be
        // counted: 600,000 fit, 500,000 more are refused (1,100,000 > 1,000,000), and 400,000
        // still fit afterwards (600,000 + 400,000 = exactly 1,000,000, allowed by ">")
        PlanRecorder r = new PlanRecorder();
        r.print("x".repeat(600_000));
        assertThrows(PlanBudgetException.class, () -> r.print("x".repeat(500_000)));
        r.print("x".repeat(400_000));
        assertEquals(2, r.printed().size());
        assertThrows(PlanBudgetException.class, () -> r.print("y"));
    }

    @Test
    void budgetRefusalsArePlanBudgetExceptions() {
        // all three recorder budgets throw PlanBudgetException - a limit (E-SCRIPT-LIMIT), not
        // an ordinary malformed-value IllegalArgumentException (E-SCHEMA)
        PlanRecorder elements = new PlanRecorder();
        for (int i = 0; i < 200_000; i++) {
            elements.removePart("");
        }
        assertThrows(PlanBudgetException.class, () -> elements.removePart(""));
        PlanRecorder chars = new PlanRecorder();
        chars.removePart(BIG_RECORDED_TEXT);
        chars.removePart(BIG_RECORDED_TEXT);
        chars.removePart(BIG_RECORDED_TEXT);
        assertThrows(PlanBudgetException.class, () -> chars.removePart(BIG_RECORDED_TEXT));
        PlanRecorder printed = new PlanRecorder();
        printed.print("x".repeat(1_000_000));
        assertThrows(PlanBudgetException.class, () -> printed.print("x"));
        // and it must stay an IllegalArgumentException so pre-budget callers still compile
        assertTrue(new PlanBudgetException("x") instanceof IllegalArgumentException);
    }
}
