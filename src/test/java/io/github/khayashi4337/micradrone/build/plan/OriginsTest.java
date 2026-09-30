package io.github.khayashi4337.micradrone.build.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.TestParts;
import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.Side;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Origins walks parent chains of plans that may not have passed the patcher, so it must end and never throw. */
class OriginsTest {
    private static final Duration WALK_TIMEOUT = Duration.ofSeconds(10);
    /** Far deeper than a thread's stack would take as recursion. */
    private static final int LONG_CHAIN = 200_000;
    private static final int PER_NODE_LIMIT = PlanPatcher.MAX_COORD;
    private static final int SUM_LIMIT = 2 * PlanPatcher.MAX_COORD;

    private final List<Issue> issues = new ArrayList<>();

    private Map<String, LocalPos> resolve(List<PlanNode> nodes) {
        return Origins.resolve(nodes, SlotResolver.NONE, issues);
    }

    private List<String> issueIds() {
        return issues.stream().map(Issue::id).toList();
    }

    private static PlanNode onSurface(String id, String parent, String wallId) {
        return new PlanNode(id, "micra:door", parent, new Anchor.OnSurface(wallId, Side.OUTER, 1, 1), Map.of(), Set.of(), "");
    }

    private static PlanNode inSlot(String id, String parent, String slotId) {
        return new PlanNode(id, "test:motor", parent, new Anchor.InSlot(slotId, Rot.NONE), Map.of(), Set.of(), "");
    }

    @Test
    void positionsAddUpAlongTheParentChain() {
        Map<String, LocalPos> o = resolve(List.of(
                TestParts.at("c", "micra:pillar", "b", 1, 1, 1),
                TestParts.at("b", "micra:pillar", "a", 10, 20, 30),
                TestParts.at("a", "micra:structure", null, 100, 200, 300)));
        assertEquals(new LocalPos(100, 200, 300), o.get("a"));
        assertEquals(new LocalPos(110, 220, 330), o.get("b"));
        assertEquals(new LocalPos(111, 221, 331), o.get("c"));
        assertTrue(issues.isEmpty(), issues.toString());
    }

    @Test
    void aParentLoopIsReportedOnceAndNothingInsideOrBelowItResolves() {
        Map<String, LocalPos> o = assertTimeoutPreemptively(WALK_TIMEOUT, () -> resolve(List.of(
                TestParts.at("c", "micra:pillar", "a", 1, 0, 0),
                TestParts.at("a", "micra:pillar", "b", 1, 0, 0),
                TestParts.at("b", "micra:pillar", "a", 1, 0, 0),
                TestParts.at("d", "micra:pillar", null, 5, 0, 0))));
        assertEquals(List.of("E-ANCHOR:a#cycle"), issueIds(), "the node where the loop closes, once");
        assertEquals(Set.of("d"), o.keySet());
        assertEquals(new LocalPos(5, 0, 0), o.get("d"));
    }

    @Test
    void aNodeThatIsItsOwnParentIsALoop() {
        Map<String, LocalPos> o = resolve(List.of(TestParts.at("a", "micra:pillar", "a", 1, 0, 0)));
        assertEquals(List.of("E-ANCHOR:a#cycle"), issueIds());
        assertTrue(o.isEmpty());
    }

    @Test
    void aVeryLongChainIsNotALoopAndDoesNotOverflowTheStack() {
        List<PlanNode> nodes = new ArrayList<>();
        // the deepest node comes first, so one walk has to climb the whole chain
        for (int i = LONG_CHAIN - 1; i >= 0; i--) {
            nodes.add(TestParts.at("n" + i, "micra:pillar", i == 0 ? null : "n" + (i - 1), 1, 0, 0));
        }
        Map<String, LocalPos> o = assertTimeoutPreemptively(WALK_TIMEOUT, () -> resolve(nodes));
        assertEquals(new LocalPos(LONG_CHAIN, 0, 0), o.get("n" + (LONG_CHAIN - 1)));
        assertEquals(LONG_CHAIN, o.size());
        assertTrue(issues.isEmpty(), issues.toString());
    }

    @Test
    void aMissingParentIsReportedOnceAndItsChildrenFollowSilently() {
        Map<String, LocalPos> o = resolve(List.of(
                TestParts.at("y", "micra:pillar", "x", 1, 0, 0),
                TestParts.at("x", "micra:pillar", "ghost", 1, 0, 0),
                TestParts.at("z", "micra:pillar", "x", 2, 0, 0)));
        assertEquals(List.of("E-ANCHOR:x#parent"), issueIds());
        assertTrue(o.isEmpty());
    }

    @Test
    void aParentTheCallerRefusedIsNeitherMissingNorReportedAgain() {
        // "m" is not in the list because the caller refused it and reported that; c and d below it have no origin, no issue
        Map<String, LocalPos> o = Origins.resolve(List.of(
                TestParts.at("c", "micra:pillar", "m", 1, 0, 0),
                TestParts.at("d", "micra:pillar", "c", 1, 0, 0),
                TestParts.at("x", "micra:pillar", "ghost", 1, 0, 0),
                TestParts.at("ok", "micra:structure", null, 4, 0, 0)), SlotResolver.NONE, issues, Set.of("m"));
        assertEquals(List.of("E-ANCHOR:x#parent"), issueIds(), "a parent that is simply not there is still reported");
        assertEquals(Set.of("ok"), o.keySet());
    }

    @Test
    void surfaceNodesAndTheirChildrenHaveNoOriginAndNoIssue() {
        Map<String, LocalPos> o = resolve(List.of(
                TestParts.at("wall", "micra:wall", null, 4, 0, 4),
                onSurface("door", null, "wall"),
                TestParts.at("knob", "micra:pillar", "door", 1, 0, 0)));
        assertEquals(Set.of("wall"), o.keySet());
        assertTrue(issues.isEmpty(), issues.toString());
    }

    @Test
    void slotsResolveThroughTheResolverAndTheParentDoesNotAddToThem() {
        SlotResolver slots = id -> "slot-a".equals(id) ? Optional.of(new LocalPos(5, 0, 5)) : Optional.empty();
        Map<String, LocalPos> o = Origins.resolve(List.of(
                TestParts.at("room", "micra:structure", null, 10, 0, 10),
                inSlot("in-a", "room", "slot-a"),
                inSlot("in-b", null, "slot-b")), slots, issues);
        assertEquals(new LocalPos(5, 0, 5), o.get("in-a"));
        assertEquals(Set.of("room", "in-a"), o.keySet());
        assertEquals(List.of("E-ANCHOR:in-b#slot"), issueIds());
    }

    @Test
    void sumsBeyondTheLimitAreReportedInsteadOfWrappingAround() {
        Map<String, LocalPos> o = resolve(List.of(
                TestParts.at("p", "micra:structure", null, PER_NODE_LIMIT, 0, 0),
                TestParts.at("c", "micra:pillar", "p", PER_NODE_LIMIT, 0, 0),
                TestParts.at("g", "micra:pillar", "c", PER_NODE_LIMIT, 0, 0),
                TestParts.at("h", "micra:pillar", "g", 1, 0, 0),
                TestParts.at("big", "micra:structure", null, Integer.MAX_VALUE, 0, 0),
                TestParts.at("wrap", "micra:pillar", "p", Integer.MAX_VALUE, 0, 0)));
        assertEquals(new LocalPos(PER_NODE_LIMIT, 0, 0), o.get("p"));
        assertEquals(new LocalPos(SUM_LIMIT, 0, 0), o.get("c"), "exactly the limit still fits");
        assertEquals(Set.of("p", "c"), o.keySet());
        assertEquals(List.of("E-ANCHOR:g#anchor", "E-ANCHOR:big#anchor", "E-ANCHOR:wrap#anchor"), issueIds());
    }

    @Test
    void theLimitIsSymmetricAndCheckedOnEveryAxis() {
        Map<String, LocalPos> o = resolve(List.of(
                TestParts.at("edge-low", "micra:structure", null, -SUM_LIMIT, 0, 0),
                TestParts.at("below", "micra:structure", null, -SUM_LIMIT - 1, 0, 0),
                TestParts.at("edge-v", "micra:structure", null, 0, SUM_LIMIT, 0),
                TestParts.at("above-v", "micra:structure", null, 0, SUM_LIMIT + 1, 0),
                TestParts.at("edge-w", "micra:structure", null, 0, 0, -SUM_LIMIT),
                TestParts.at("above-w", "micra:structure", null, 0, 0, SUM_LIMIT + 1)));
        assertEquals(Set.of("edge-low", "edge-v", "edge-w"), o.keySet());
        assertEquals(List.of("E-ANCHOR:below#anchor", "E-ANCHOR:above-v#anchor", "E-ANCHOR:above-w#anchor"), issueIds());
    }
}
