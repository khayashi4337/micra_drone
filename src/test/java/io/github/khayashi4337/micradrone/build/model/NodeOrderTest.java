package io.github.khayashi4337.micradrone.build.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NodeOrderTest {
    private static final Anchor ORIGIN = new Anchor.Absolute(new LocalPos(0, 0, 0), Rot.NONE);

    private static PlanNode node(String id, String parent, Anchor anchor) {
        return new PlanNode(id, "micra:wall", parent, anchor, Map.of(), Set.of(), "");
    }

    private static PlanNode free(String id) {
        return node(id, null, ORIGIN);
    }

    private static PlanNode onWall(String id, String wallId) {
        return node(id, null, new Anchor.OnSurface(wallId, Side.OUTER, 1, 0));
    }

    private static List<String> ids(List<PlanNode> nodes) {
        return nodes.stream().map(PlanNode::id).toList();
    }

    @Test
    void aListAlreadyInDependencyOrderComesOutUnchanged() {
        List<PlanNode> nodes = List.of(free("hut"), node("wall-n", "hut", ORIGIN), node("door-1", "hut", new Anchor.OnSurface("wall-n", Side.INNER, 3, 0)),
                free("pillar-1"));
        NodeOrder.Result r = NodeOrder.of(nodes);
        assertEquals(nodes, r.placed());
        assertEquals(List.of(), r.stuck());
    }

    @Test
    void aNodeWaitsForItsWallAndTheEarliestReadyNodeGoesNext() {
        // stored: door-1, pillar-1, wall-1, pillar-2; the door rests on the wall. Hand-derived: ready at first are
        // pillar-1 (position 1), wall-1 (2) and pillar-2 (3). pillar-1 goes, then wall-1 (the earliest ready), which
        // frees door-1 (position 0, earlier than pillar-2), so door-1 goes next and pillar-2 last.
        NodeOrder.Result r = NodeOrder.of(List.of(onWall("door-1", "wall-1"), free("pillar-1"), free("wall-1"), free("pillar-2")));
        assertEquals(List.of("pillar-1", "wall-1", "door-1", "pillar-2"), ids(r.placed()));
        assertEquals(List.of(), r.stuck());
    }

    @Test
    void aNodeWaitsForItsParentEvenWhenTheListHasTheChildFirst() {
        // a loosely read list may name a child before its parent
        NodeOrder.Result r = NodeOrder.of(List.of(node("child", "parent", ORIGIN), free("parent")));
        assertEquals(List.of("parent", "child"), ids(r.placed()));
    }

    @Test
    void aParentAndAWallThatAreTheSameNodeAreOneDependencyNotTwo() {
        // door-1 has wall-1 as its parent AND rests on it: it becomes ready as soon as wall-1 is written
        NodeOrder.Result r = NodeOrder.of(List.of(node("door-1", "wall-1", new Anchor.OnSurface("wall-1", Side.OUTER, 1, 0)), free("wall-1")));
        assertEquals(List.of("wall-1", "door-1"), ids(r.placed()));
        assertEquals(List.of(), r.stuck());
    }

    @Test
    void referencesToIdsThatAreNotNodesOfTheListAreIgnored() {
        List<PlanNode> nodes = List.of(node("a", "no-such-parent", ORIGIN), onWall("b", "no-such-wall"), free("c"));
        NodeOrder.Result r = NodeOrder.of(nodes);
        assertEquals(nodes, r.placed());
        assertEquals(List.of(), r.stuck());
    }

    @Test
    void aLoopIsStuckTogetherWithWhateverWaitsForIt() {
        // w1 and w2 hang below each other, w3 rests on w1: none of the three can be written; the free nodes can
        List<PlanNode> nodes = List.of(free("free-1"), node("w1", "w2", ORIGIN), node("w2", "w1", ORIGIN), onWall("w3", "w1"), free("free-2"));
        NodeOrder.Result r = NodeOrder.of(nodes);
        assertEquals(List.of("free-1", "free-2"), ids(r.placed()));
        assertEquals(List.of("w1", "w2", "w3"), ids(r.stuck()), "the stuck nodes keep the order of the list");
    }

    @Test
    void aNodeThatRestsOnItselfIsStuck() {
        NodeOrder.Result r = NodeOrder.of(List.of(onWall("w1", "w1"), free("w2")));
        assertEquals(List.of("w2"), ids(r.placed()));
        assertEquals(List.of("w1"), ids(r.stuck()));
    }

    @Test
    void aRepeatedIdIsResolvedToItsFirstNode() {
        // y hangs below "x": it waits for the FIRST x; the second x is just another node that needs nothing
        List<PlanNode> nodes = List.of(free("x"), node("y", "x", ORIGIN), free("x"));
        NodeOrder.Result r = NodeOrder.of(nodes);
        assertEquals(List.of("x", "y", "x"), ids(r.placed()));
        assertEquals(List.of(), r.stuck());
    }

    @Test
    void anEmptyListGivesNothing() {
        NodeOrder.Result r = NodeOrder.of(List.of());
        assertEquals(List.of(), r.placed());
        assertEquals(List.of(), r.stuck());
    }

    @Test
    void theResultDoesNotChangeTheInputAndItsListsCannotBeChanged() {
        List<PlanNode> nodes = new ArrayList<>(List.of(onWall("door-1", "wall-1"), free("wall-1")));
        NodeOrder.Result r = NodeOrder.of(nodes);
        assertEquals(List.of("door-1", "wall-1"), ids(nodes));
        assertThrows(UnsupportedOperationException.class, () -> r.placed().add(free("more")));
        assertThrows(UnsupportedOperationException.class, () -> r.stuck().add(free("more")));
    }

    /** Deep enough that a recursive ordering would overflow the stack and a quadratic one would take minutes. */
    private static final int LONG_CHAIN = 100_000;

    @Test
    void aChainOfOneHundredThousandNodesIsOrderedWithoutRecursionOrDelay() {
        // node i rests on the wall i+1: the whole list has to come out backwards
        int length = LONG_CHAIN;
        List<PlanNode> nodes = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            nodes.add(i == length - 1 ? free("n" + i) : onWall("n" + i, "n" + (i + 1)));
        }
        NodeOrder.Result r = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> NodeOrder.of(nodes));
        assertEquals(length, r.placed().size());
        assertEquals(List.of(), r.stuck());
        assertEquals("n" + (length - 1), r.placed().get(0).id());
        assertEquals("n0", r.placed().get(length - 1).id());
        for (int i = 0; i < length; i++) {
            assertEquals("n" + (length - 1 - i), r.placed().get(i).id());
        }
    }
}
