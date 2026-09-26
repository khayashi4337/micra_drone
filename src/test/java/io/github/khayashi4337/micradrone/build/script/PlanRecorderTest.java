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
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanOp;
import io.github.khayashi4337.micradrone.build.model.PlanPatch;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.Routing;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.lang.MicraFunction;
import io.github.khayashi4337.micradrone.lang.MicraNone;
import io.github.khayashi4337.micradrone.lang.PlanAnchorArgs;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
}
