package io.github.khayashi4337.micradrone.build.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.TestParts;
import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.ConnKind;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Constraints;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.LogisticsPlan;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
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
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The patcher with its dependency index against {@link ScanningPlanPatcher}, the scanning implementation it replaced:
 * random operation sequences are applied one op at a time to both, and every op must produce the same issues and leave
 * the same plan. After every op the index (when it exists) must hold a valid topological order.
 */
class PlanPatcherDifferentialTest {
    private static final int SEEDS = 300;
    private static final int OPS_PER_SEED = 250;
    /** Random hand-built base plans (with loops and dangling references) and the ops applied to each. */
    private static final int BROKEN_BASE_SEEDS = 300;
    private static final int OPS_PER_BROKEN_BASE = 120;
    private static final int BROKEN_BASE_NODES = 30;
    /** A floor on each kind of compared decision, so a generator that stops producing one kind is noticed. */
    private static final int MIN_DECISIONS_OF_EACH_KIND = SEEDS;
    private static final Duration BOUND = Duration.ofSeconds(60);
    private static final Duration HAND_CASE_BOUND = Duration.ofSeconds(5);

    // Chances (out of OP_KINDS) of each op kind; the rest is split by the cumulative thresholds below.
    private static final int OP_KINDS = 100;
    private static final int ADD_UNTIL = 30;
    private static final int MOVE_UNTIL = 60;
    private static final int REMOVE_UNTIL = 75;
    private static final int UPDATE_UNTIL = 80;
    private static final int CONNECT_UNTIL = 90;
    private static final int DISCONNECT_UNTIL = 95;
    /** Out of OP_KINDS: a move chosen so that it closes a loop (node taken among the wall's own dependencies). */
    private static final int LOOP_MOVE_PERCENT = 35;
    /** Out of OP_KINDS: a reference that names something absent or wrong. */
    private static final int BAD_REF_PERCENT = 5;
    private static final int HALF = 50;
    private static final int MAX_LIST = 3;

    private static final String NODE_PREFIX = "n";
    private static final String CONNECTION_PREFIX = "c";
    private static final String DOCK_PREFIX = "d";
    private static final String GHOST_ID = "ghost";
    private static final String DOOR = "micra:door";
    private static final String MOTOR = "test:motor";
    private static final String SHAFT = "test:shaft";
    private static final String PORT_OUT = "out";
    private static final String PORT_IN = "in";
    private static final String SIDE_PARAM = "side";
    private static final String CYCLE_KEY = "#cycle";
    private static final String REMOVE_KEY = "#remove";
    private static final String PLAN_ID = "plan";
    private static final List<String> SIDES = List.of("north", "south", "east", "west");
    private static final List<String> TYPES = List.of(BuildingParts.WALL, BuildingParts.WALL, DOOR, MOTOR, SHAFT);

    private final PlanPatcher real = new PlanPatcher(TestParts.registry(), TestParts.bundle());
    private final ScanningPlanPatcher reference = new ScanningPlanPatcher(TestParts.registry(), TestParts.bundle());

    /** How many decisions of each kind were compared. */
    private static final class Tally {
        int accepts;
        int loopRefusals;
        int dependentsRefusals;
        int otherRefusals;
        int opsCompared;
        /** Moves onto a wall whose label was above the node's, by how the index handled them. */
        int movesInOrder;
        int movesWallTakesLowest;
        int movesNodeTakesHighest;
        int movesSearched;

        void count(List<Issue> issues) {
            opsCompared++;
            if (issues.stream().noneMatch(Issue::isError)) {
                accepts++;
            } else if (issues.stream().anyMatch(i -> i.id().endsWith(CYCLE_KEY))) {
                loopRefusals++;
            } else if (issues.stream().anyMatch(i -> i.id().endsWith(REMOVE_KEY))) {
                dependentsRefusals++;
            } else {
                otherRefusals++;
            }
        }

        @Override
        public String toString() {
            return "ops " + opsCompared + ", accepts " + accepts + ", loop refusals " + loopRefusals
                    + ", dependents refusals " + dependentsRefusals + ", other refusals " + otherRefusals
                    + "; moves between labelled nodes: already in order " + movesInOrder + ", wall takes the lowest label "
                    + movesWallTakesLowest + ", node takes the highest label " + movesNodeTakesHighest
                    + ", searched " + movesSearched;
        }
    }

    // ------------------------------------------------------------------ the generator

    /** Makes random ops against the reference state (the state both implementations should share). */
    private static final class Gen {
        final Random rnd;
        final ScanningPlanPatcher.State st;
        int nextId;

        Gen(Random rnd, ScanningPlanPatcher.State st, int firstId) {
            this.rnd = rnd;
            this.st = st;
            this.nextId = firstId;
        }

        boolean chance(int percent) {
            return rnd.nextInt(OP_KINDS) < percent;
        }

        <T> T pick(List<T> list) {
            return list.get(rnd.nextInt(list.size()));
        }

        List<String> nodeIds() {
            return new ArrayList<>(st.nodes.keySet());
        }

        List<String> nodesOfType(String type) {
            return st.nodes.values().stream().filter(n -> n.type().equals(type)).map(PlanNode::id).toList();
        }

        /** An existing node, now and then an absent one. */
        String someNode() {
            List<String> ids = nodeIds();
            return ids.isEmpty() || chance(BAD_REF_PERCENT) ? GHOST_ID : pick(ids);
        }

        /** An existing wall, now and then something that is not one. */
        String someWall() {
            List<String> walls = nodesOfType(BuildingParts.WALL);
            return walls.isEmpty() || chance(BAD_REF_PERCENT) ? someNode() : pick(walls);
        }

        Anchor surfaceOn(String wallId) {
            return new Anchor.OnSurface(wallId, rnd.nextBoolean() ? Side.OUTER : Side.INNER, rnd.nextInt(MAX_LIST), 0);
        }

        Anchor absolute() {
            return new Anchor.Absolute(new LocalPos(rnd.nextInt(MAX_LIST), 0, rnd.nextInt(MAX_LIST)), Rot.NONE);
        }

        Map<String, ParamValue> paramsFor(String type) {
            return type.equals(BuildingParts.WALL) ? Map.of(SIDE_PARAM, new StrV(pick(SIDES))) : Map.of();
        }

        PlanOp next() {
            int kind = rnd.nextInt(OP_KINDS);
            if (kind < ADD_UNTIL) {
                return add();
            }
            if (kind < MOVE_UNTIL) {
                return move();
            }
            if (kind < REMOVE_UNTIL) {
                return new PlanOp.RemoveNode(someNode());
            }
            if (kind < UPDATE_UNTIL) {
                String id = someNode();
                PlanNode n = st.nodes.get(id);
                return new PlanOp.UpdateParams(id, n == null ? Map.of() : paramsFor(n.type()));
            }
            if (kind < CONNECT_UNTIL) {
                return connect();
            }
            if (kind < DISCONNECT_UNTIL) {
                List<String> ids = new ArrayList<>(st.connections.keySet());
                return new PlanOp.RemoveConnection(ids.isEmpty() || chance(BAD_REF_PERCENT) ? GHOST_ID : pick(ids));
            }
            return logistics();
        }

        PlanOp add() {
            // now and then an id that is taken or was removed, so duplicates and re-used ids are compared too
            String id = chance(BAD_REF_PERCENT) ? someNode() : NODE_PREFIX + nextId++;
            String type = pick(TYPES);
            String parent = rnd.nextBoolean() ? null : someNode();
            Anchor anchor = rnd.nextBoolean() ? absolute() : surfaceOn(someWall());
            return new PlanOp.AddNode(new PlanNode(id, type, parent, anchor, paramsFor(type), Set.of(), ""));
        }

        PlanOp move() {
            if (chance(HALF / 2)) {
                return new PlanOp.MoveNode(someNode(), absolute());
            }
            String wall = someWall();
            String mover = chance(LOOP_MOVE_PERCENT) ? someDependencyOf(wall) : someNode();
            return new PlanOp.MoveNode(mover, surfaceOn(wall));
        }

        /** A random node the wall depends on (itself included), found by walking parents and walls. */
        String someDependencyOf(String wallId) {
            List<String> found = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            ArrayDeque<String> pending = new ArrayDeque<>();
            pending.push(wallId);
            while (!pending.isEmpty()) {
                String id = pending.pop();
                PlanNode n = st.nodes.get(id);
                if (n == null || !seen.add(id)) {
                    continue;
                }
                found.add(id);
                if (n.parent() != null) {
                    pending.push(n.parent());
                }
                if (n.anchor() instanceof Anchor.OnSurface s) {
                    pending.push(s.nodeId());
                }
            }
            return found.isEmpty() ? someNode() : pick(found);
        }

        List<String> someIds(int max) {
            List<String> ids = new ArrayList<>();
            int n = rnd.nextInt(max + 1);
            for (int i = 0; i < n; i++) {
                ids.add(someNode());
            }
            return ids;
        }

        PlanOp connect() {
            List<String> motors = nodesOfType(MOTOR);
            List<String> shafts = nodesOfType(SHAFT);
            String from = motors.isEmpty() ? someNode() : pick(motors);
            String to = shafts.isEmpty() ? someNode() : pick(shafts);
            Routing routing = rnd.nextBoolean() ? Routing.AUTO : new Routing.Explicit(someIds(MAX_LIST));
            Constraints constraints = new Constraints(null, Set.copyOf(someIds(MAX_LIST)), null, Set.of());
            String id = chance(BAD_REF_PERCENT) && !st.connections.isEmpty()
                    ? pick(new ArrayList<>(st.connections.keySet())) : CONNECTION_PREFIX + nextId++;
            return new PlanOp.AddConnection(new Connection(id, new PortRef(from, PORT_OUT), new PortRef(to, PORT_IN),
                    ConnKind.ROTATION, routing, constraints));
        }

        PlanOp logistics() {
            if (chance(BAD_REF_PERCENT)) {
                return new PlanOp.SetLogistics(null);
            }
            List<LogisticsPlan.Dock> docks = new ArrayList<>();
            int n = rnd.nextInt(MAX_LIST + 1);
            for (int i = 0; i < n; i++) {
                List<PortRef> ports = new ArrayList<>();
                for (String id : someIds(MAX_LIST)) {
                    ports.add(new PortRef(id, PORT_OUT));
                }
                docks.add(new LogisticsPlan.Dock(DOCK_PREFIX + rnd.nextInt(MAX_LIST * MAX_LIST), new Box(0, 0, 0, 1, 0, 1),
                        new Box(0, 1, 0, 1, 4, 1), Facing.NORTH, ports, someIds(MAX_LIST)));
            }
            return new PlanOp.SetLogistics(new LogisticsPlan(docks, List.of(), List.of()));
        }
    }

    // ------------------------------------------------------------------ comparing

    private static void assertSameState(PlanPatcher.State mine, ScanningPlanPatcher.State theirs, String where) {
        assertEquals(new ArrayList<>(theirs.nodes.values()), new ArrayList<>(mine.nodes.values()), where);
        assertEquals(new ArrayList<>(theirs.connections.values()), new ArrayList<>(mine.connections.values()), where);
        assertEquals(theirs.logistics, mine.logistics, where);
    }

    private static void assertIndexValid(PlanPatcher.State st, boolean force, String where) {
        DependencyIndex index = force ? st.index() : st.builtIndex();
        if (index != null) {
            assertEquals(List.of(), index.invariantViolations(), where);
        }
    }

    /**
     * Applies {@code ops} random ops to both implementations starting from {@code base}, one op at a time, comparing
     * the issues of every op and the state after it. Half of the seeds build the index at the first op and check it
     * after every op; the other half leave it to the patcher (built at the first move onto a wall or removal).
     */
    private void compare(SemanticPlan base, Random rnd, int ops, boolean forceIndex, int firstId, Tally tally, String seed) {
        PlanPatcher.State mine = new PlanPatcher.State(base);
        ScanningPlanPatcher.State theirs = new ScanningPlanPatcher.State(base);
        Gen gen = new Gen(rnd, theirs, firstId);
        for (int k = 0; k < ops; k++) {
            PlanOp op = gen.next();
            String where = seed + " op " + k + ": " + op;
            countMoveKind(mine, theirs, op, tally);
            List<Issue> expected = new ArrayList<>();
            reference.applyOp(theirs, op, expected);
            List<Issue> actual = new ArrayList<>();
            real.applyOp(mine, op, actual);
            assertEquals(expected, actual, where);
            tally.count(expected);
            assertSameState(mine, theirs, where);
            assertIndexValid(mine, forceIndex, where);
        }
    }

    /**
     * Which branch of {@link DependencyIndex#allowsResting} a move onto a wall takes, read from the labels before the
     * op, so the report can say how often the Pearce-Kelly search itself was compared (not only its O(1) cases).
     */
    private static void countMoveKind(PlanPatcher.State mine, ScanningPlanPatcher.State theirs, PlanOp op, Tally tally) {
        DependencyIndex index = mine.builtIndex();
        if (index == null || !(op instanceof PlanOp.MoveNode m) || !(m.anchor() instanceof Anchor.OnSurface s)
                || !theirs.nodes.containsKey(m.id()) || m.id().equals(s.nodeId())) {
            return;
        }
        Long wallLabel = index.labelOf(s.nodeId());
        Long nodeLabel = index.labelOf(m.id());
        if (wallLabel == null || nodeLabel == null || !theirs.nodes.get(s.nodeId()).type().equals(BuildingParts.WALL)) {
            return;
        }
        if (wallLabel < nodeLabel) {
            tally.movesInOrder++;
        } else if (DependencyIndex.dependenciesOf(theirs.nodes.get(s.nodeId())).stream().noneMatch(theirs.nodes::containsKey)) {
            tally.movesWallTakesLowest++;
        } else if (theirs.nodes.values().stream().noneMatch(n -> DependencyIndex.dependenciesOf(n).contains(m.id()))) {
            tally.movesNodeTakesHighest++;
        } else {
            tally.movesSearched++;
        }
    }

    private static SemanticPlan empty() {
        return SemanticPlan.empty(PLAN_ID);
    }

    @Test
    void randomOpSequencesDecideExactlyLikeTheScanningImplementation() {
        Tally tally = new Tally();
        assertTimeoutPreemptively(BOUND, () -> {
            for (int seed = 0; seed < SEEDS; seed++) {
                compare(empty(), new Random(seed), OPS_PER_SEED, seed % 2 == 0, 0, tally, "seed " + seed);
            }
        });
        System.out.println("[differential] valid base plans: " + tally);
        assertTrue(tally.accepts >= MIN_DECISIONS_OF_EACH_KIND, tally.toString());
        assertTrue(tally.loopRefusals >= MIN_DECISIONS_OF_EACH_KIND, tally.toString());
        assertTrue(tally.dependentsRefusals >= MIN_DECISIONS_OF_EACH_KIND, tally.toString());
        assertTrue(tally.movesSearched >= MIN_DECISIONS_OF_EACH_KIND, tally.toString());
    }

    @Test
    void wholePatchesOfOneOpGiveTheSameResultsAsTheScanningImplementation() {
        // the public entry: each op as a patch of its own, so the index is built afresh from every intermediate plan
        Tally tally = new Tally();
        assertTimeoutPreemptively(BOUND, () -> {
            for (int seed = 0; seed < SEEDS; seed++) {
                Random rnd = new Random(seed);
                ScanningPlanPatcher.State shadow = new ScanningPlanPatcher.State(empty());
                Gen gen = new Gen(rnd, shadow, 0);
                SemanticPlan plan = empty();
                for (int k = 0; k < OPS_PER_SEED; k++) {
                    PlanOp op = gen.next();
                    PlanPatch patch = new PlanPatch("p", plan.revision(), "diff", List.of(op));
                    PatchResult expected = reference.apply(plan, patch);
                    PatchResult actual = real.apply(plan, patch);
                    assertEquals(expected, actual, "seed " + seed + " op " + k + ": " + op);
                    tally.count(expected.issues());
                    reference.applyOp(shadow, op, new ArrayList<>());
                    if (expected.ok()) {
                        plan = expected.plan();
                    }
                }
            }
        });
        System.out.println("[differential] one-op patches: " + tally);
        assertTrue(tally.loopRefusals >= MIN_DECISIONS_OF_EACH_KIND, tally.toString());
        assertTrue(tally.dependentsRefusals >= MIN_DECISIONS_OF_EACH_KIND, tally.toString());
    }

    // ------------------------------------------------------------------ base plans the invariant cannot describe

    private static PlanNode wall(String id, String parent, Anchor anchor) {
        return new PlanNode(id, BuildingParts.WALL, parent, anchor, Map.of(SIDE_PARAM, new StrV(SIDES.get(0))), Set.of(), "");
    }

    private static Anchor onWall(String wallId) {
        return new Anchor.OnSurface(wallId, Side.OUTER, 0, 0);
    }

    private static Anchor origin() {
        return new Anchor.Absolute(new LocalPos(0, 0, 0), Rot.NONE);
    }

    private static SemanticPlan handBuilt(List<PlanNode> nodes) {
        return new SemanticPlan(1, PLAN_ID, 1, 0, null, StyleSpec.EMPTY, nodes, List.of(), null, Provenance.NONE);
    }

    /** A hand-built plan of walls whose parents and walls point anywhere: loops, self-loops and absent ids. */
    private static SemanticPlan brokenBase(Random rnd) {
        List<PlanNode> nodes = new ArrayList<>();
        for (int i = 0; i < BROKEN_BASE_NODES; i++) {
            // an id one past the last one is absent until the ops add it (the generator's ids start there)
            String parent = rnd.nextBoolean() ? null : NODE_PREFIX + rnd.nextInt(BROKEN_BASE_NODES + 1);
            Anchor anchor = rnd.nextBoolean() ? origin() : onWall(NODE_PREFIX + rnd.nextInt(BROKEN_BASE_NODES + 1));
            nodes.add(wall(NODE_PREFIX + i, parent, anchor));
        }
        return handBuilt(nodes);
    }

    @Test
    void randomBasePlansWithLoopsAndDanglingReferencesDecideExactlyLikeTheScanningImplementation() {
        Tally tally = new Tally();
        assertTimeoutPreemptively(BOUND, () -> {
            for (int seed = 0; seed < BROKEN_BASE_SEEDS; seed++) {
                Random rnd = new Random(seed);
                compare(brokenBase(rnd), rnd, OPS_PER_BROKEN_BASE, seed % 2 == 0, BROKEN_BASE_NODES, tally,
                        "broken seed " + seed);
            }
        });
        System.out.println("[differential] broken base plans: " + tally);
        assertTrue(tally.accepts >= MIN_DECISIONS_OF_EACH_KIND, tally.toString());
        assertTrue(tally.loopRefusals >= MIN_DECISIONS_OF_EACH_KIND, tally.toString());
        assertTrue(tally.dependentsRefusals >= MIN_DECISIONS_OF_EACH_KIND, tally.toString());
        assertTrue(tally.movesSearched >= MIN_DECISIONS_OF_EACH_KIND, tally.toString());
    }

    private PatchResult applyWithin(SemanticPlan plan, PlanOp... ops) {
        return assertTimeoutPreemptively(HAND_CASE_BOUND,
                () -> real.apply(plan, new PlanPatch("p", plan.revision(), "hand", List.of(ops))));
    }

    private static List<String> issueIds(PatchResult r) {
        return r.issues().stream().map(Issue::id).toList();
    }

    @Test
    void aLoopInTheBasePlanIsWalkedAroundAndStillAnswersBothQuestions() {
        // w1 and w2 rest on each other (a loop no patch could make); w3 stands alone
        SemanticPlan looped = handBuilt(List.of(wall("w1", null, onWall("w2")), wall("w2", null, onWall("w1")),
                wall("w3", null, origin())));
        // hand-derived: w3 resting on w1 closes nothing (w1 does not depend on w3)
        assertTrue(applyWithin(looped, new PlanOp.MoveNode("w3", onWall("w1"))).ok());
        // w1 resting on w3 closes nothing either (w3 depends on nothing); it breaks the old loop
        assertTrue(applyWithin(looped, new PlanOp.MoveNode("w1", onWall("w3"))).ok());
        // once w3 rests on w1, w1 resting on w3 is a loop
        assertEquals(List.of("E-ANCHOR:w1#cycle"), issueIds(applyWithin(looped,
                new PlanOp.MoveNode("w3", onWall("w1")), new PlanOp.MoveNode("w1", onWall("w3")))));
        // w1 cannot go: w2 rests on it (the loop does not hide that)
        PatchResult remove = applyWithin(looped, new PlanOp.RemoveNode("w1"));
        assertEquals(List.of("E-ANCHOR:w1#remove"), issueIds(remove));
        assertEquals("w2", remove.issues().get(0).data().get("dependents"));
        // a new wall on top of the loop, then a node moved onto that new wall
        PatchResult onTop = applyWithin(looped, new PlanOp.AddNode(wall("w4", null, onWall("w2"))),
                new PlanOp.MoveNode("w3", onWall("w4")));
        assertTrue(onTop.ok(), onTop.issues().toString());
        // and w4 resting on w3 now would be a loop w4 -> w3 -> w4
        assertEquals(List.of("E-ANCHOR:w4#cycle"), issueIds(applyWithin(onTop.plan(), new PlanOp.MoveNode("w4", onWall("w3")))));
    }

    @Test
    void aSelfLoopInTheBasePlanNeitherHangsNorThrows() {
        // w1 is its own parent; w2 rests on w1
        SemanticPlan looped = handBuilt(List.of(wall("w1", "w1", origin()), wall("w2", null, onWall("w1"))));
        PatchResult remove = applyWithin(looped, new PlanOp.RemoveNode("w1"));
        // hand-derived: both w1 (its own parent) and w2 (rests on it) depend on w1, in dictionary order
        assertEquals("w1,w2", remove.issues().get(0).data().get("dependents"));
        assertEquals(List.of("E-ANCHOR:w1#cycle"), issueIds(applyWithin(looped, new PlanOp.MoveNode("w1", onWall("w2")))));
        assertTrue(applyWithin(looped, new PlanOp.MoveNode("w2", onWall("w1"))).ok());
    }

    @Test
    void aDanglingReferenceBecomesARealDependencyWhenTheMissingNodeIsAdded() {
        // door-1 rests on "a", which is not in the plan; wall "b" hangs below the absent "a" too
        PlanNode door = new PlanNode("door-1", DOOR, null, onWall("a"), Map.of(), Set.of(), "");
        SemanticPlan dangling = handBuilt(List.of(door, wall("b", "a", origin()), wall("c", null, origin())));
        PatchResult added = applyWithin(dangling, new PlanOp.AddNode(wall("a", null, origin())));
        assertTrue(added.ok(), added.issues().toString());
        // hand-derived: both now depend on a
        PatchResult remove = applyWithin(added.plan(), new PlanOp.RemoveNode("a"));
        assertEquals("b,door-1", remove.issues().get(0).data().get("dependents"));
        // a resting on b is a loop now (b hangs below a); in the same patch as the add, too
        assertEquals(List.of("E-ANCHOR:a#cycle"), issueIds(applyWithin(added.plan(), new PlanOp.MoveNode("a", onWall("b")))));
        assertEquals(List.of("E-ANCHOR:a#cycle"), issueIds(applyWithin(dangling,
                new PlanOp.MoveNode("c", onWall("b")), new PlanOp.AddNode(wall("a", null, origin())),
                new PlanOp.MoveNode("a", onWall("b")))));
        // a added so that it closes a loop through the dangling reference (the add is not checked, as before)
        PatchResult loopByAdd = applyWithin(dangling, new PlanOp.AddNode(wall("a", null, onWall("b"))));
        assertTrue(loopByAdd.ok(), loopByAdd.issues().toString());
        // hand-derived: c resting on b closes nothing (b and a depend only on each other); but once c rests on a,
        // a resting on c would close a -> c -> a
        assertTrue(applyWithin(loopByAdd.plan(), new PlanOp.MoveNode("c", onWall("b"))).ok());
        assertEquals(List.of("E-ANCHOR:a#cycle"), issueIds(applyWithin(loopByAdd.plan(),
                new PlanOp.MoveNode("c", onWall("a")), new PlanOp.MoveNode("a", onWall("c")))));
    }

    @Test
    void theIndexOfAPlanWithALoopMarksTheLoopAndEverythingBelowIt() {
        // x rests on the loop y <-> z; v rests on x; u stands alone
        SemanticPlan looped = handBuilt(List.of(wall("u", null, origin()), wall("x", null, onWall("y")),
                wall("y", null, onWall("z")), wall("z", null, onWall("y")), wall("v", null, onWall("x"))));
        PlanPatcher.State st = new PlanPatcher.State(looped);
        DependencyIndex index = st.index();
        assertNotNull(index);
        // hand-derived: only u can be labelled; y and z sit in the loop, x and v below it
        assertEquals(Set.of("x", "y", "z", "v"), index.unorderedNodes());
        assertEquals(List.of(), index.invariantViolations());
        assertNull(new PlanPatcher.State(looped).builtIndex(), "the index is only built when a question needs it");
    }
}
