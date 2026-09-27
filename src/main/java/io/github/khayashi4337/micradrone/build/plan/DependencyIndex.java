package io.github.khayashi4337.micradrone.build.plan;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.LogisticsPlan;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.PortRef;
import io.github.khayashi4337.micradrone.build.model.Routing;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

/**
 * What depends on what in a plan being patched, kept up to date op by op so that {@link PlanPatcher} can answer its
 * two questions without scanning the plan: "would resting this node on that wall close a loop?" and "which nodes,
 * connections and docks still name this node?".
 *
 * <p><b>The dependencies.</b> A node depends on its parent and on the wall its {@link Anchor.OnSurface} anchor rests
 * on. Only nodes that are in the plan count; a reference to an absent id (possible only in a hand-built base plan) is
 * remembered in the reverse map, so that it becomes a real dependency if a node with that id is added later.
 *
 * <p><b>The reverse maps</b> give, for any id, the nodes that depend on it, the connections that name it (either end,
 * a node it routes through or avoids) and the docks that name it (a linked port or a docking connector). Each op
 * updates only the entries of what it touches, so "who depends on this node?" costs O(answer), not O(plan).
 *
 * <p><b>The order labels.</b> Every node gets a number, its label, with the invariant "every dependency has a smaller
 * label than its dependent". Then a new dependency "y rests on x" can only close a loop if y already reaches x, and
 * every node on such a path has a label between those of y and x. This is the dynamic topological ordering of Pearce
 * and Kelly:
 * <ul>
 * <li>a new node takes the next number above all others, O(1): nothing depends on it yet;
 * <li>removing a node or a dependency never breaks the invariant, O(1);
 * <li>adding the dependency "y rests on x" when label(x) &lt; label(y) already agrees with the order: O(1), no search;
 * <li>otherwise search only the affected region: forward from y through its dependents whose labels are below
 *     label(x) (if this reaches x, the new dependency closes a loop and nothing changes), and backward from x through
 *     its dependencies whose labels are above label(y). Every node that must move is in one of the two sets. Their
 *     labels are pooled and handed out again, the backward set first and then the forward set, each in its old
 *     relative order, so x and everything it needs end up below y and everything that needs y. The cost is
 *     O(F log F) for the F nodes of the two sets (the sort), never more than the region between the two labels.
 * </ul>
 * Two O(1) cases are taken before that search: when x depends on nothing it simply takes a number below all others,
 * and when nothing depends on y it takes a number above all others. Without them a chain built "against" the label
 * order (each wall resting on a wall added after it) makes every search cover the whole chain built so far, which is
 * quadratic; with them both directions of building such a chain in order stay O(1) per move. The search is still
 * needed when two chains that each have more than one node are joined against the order; that is the one case whose
 * cost depends on the plan (the A2 report measures it).
 *
 * <p><b>Plans the invariant cannot describe.</b> A hand-built base plan may already hold a loop. The labels are first
 * given by Kahn's algorithm (repeatedly label a node whose dependencies are all labelled), which always ends; the
 * nodes it cannot label - those in a loop and everything that depends on them - are kept in an <em>unordered</em>
 * set, which is closed under "depends on" (a dependent of an unordered node is unordered). A node with an absent
 * dependency that is added later is treated the same way: the new node and all that depends on it become unordered.
 * Any loop question that touches an unordered node is answered by the old bounded walk (each node visited once); the
 * reverse maps need no order and work for every plan.
 *
 * <p>The index reads the patcher's live node, connection and logistics state; the patcher calls the update methods
 * right after it changes that state, and never builds a loop of its own (it asks {@link #allowsResting} first).
 */
final class DependencyIndex {
    private final Map<String, PlanNode> nodes;
    private final Map<String, Connection> connections;
    private LogisticsPlan logistics;

    /** For every id some node names as parent or wall (present or not): those nodes. */
    private final Map<String, Set<String>> nodeDependents = new HashMap<>();
    private final Map<String, Set<String>> connectionsNaming = new HashMap<>();
    private final Map<String, Set<String>> docksNaming = new HashMap<>();

    /** The order label of a labelled node, and the mark a search leaves on it (instead of a visited set). */
    private static final class Slot {
        final String id;
        long label;
        long mark;

        Slot(String id, long label) {
            this.id = id;
            this.label = label;
        }
    }

    private static final Comparator<Slot> BY_LABEL = Comparator.comparingLong(slot -> slot.label);

    /** The slot of every node that has a label; the others are in {@link #unordered}. */
    private final Map<String, Slot> slots = new HashMap<>();
    private final Set<String> unordered = new HashSet<>();
    /** No label is below {@code lowest} or above {@code highest}, so {@code --lowest} and {@code ++highest} are new. */
    private long lowest;
    private long highest = -1;
    /** The mark of the latest search; each search takes a new one, so old marks never need clearing. */
    private long searchMark;

    /** Builds the index of the state in O(n log n + references), labelling the nodes by Kahn's algorithm. */
    DependencyIndex(Map<String, PlanNode> nodes, Map<String, Connection> connections, LogisticsPlan logistics) {
        this.nodes = nodes;
        this.connections = connections;
        for (PlanNode n : nodes.values()) {
            for (String dep : dependenciesOf(n)) {
                link(nodeDependents, dep, n.id());
            }
        }
        for (Connection c : connections.values()) {
            connectionAdded(c);
        }
        logisticsSet(logistics);
        labelByKahn();
    }

    /**
     * Kahn's algorithm: label a node once all its dependencies are labelled. Among the nodes that are ready, the one
     * stored first goes first, so a plan whose stored order already respects its dependencies (every plan the patcher
     * builds without relocations) is labelled in exactly that order, and later adds and moves that follow the stored
     * order need no search. O(n log n) for the priority queue.
     */
    private void labelByKahn() {
        String[] ids = nodes.keySet().toArray(new String[0]);
        Map<String, Integer> position = new HashMap<>();
        int[] waiting = new int[ids.length];
        PriorityQueue<Integer> ready = new PriorityQueue<>();
        for (int i = 0; i < ids.length; i++) {
            position.put(ids[i], i);
            waiting[i] = presentDependencies(nodes.get(ids[i])).size();
            if (waiting[i] == 0) {
                ready.add(i);
            }
        }
        while (!ready.isEmpty()) {
            int i = ready.poll();
            slots.put(ids[i], new Slot(ids[i], ++highest));
            for (String d : dependentsIn(nodeDependents, ids[i])) {
                int j = position.get(d);
                if (--waiting[j] == 0) {
                    ready.add(j);
                }
            }
        }
        for (int i = 0; i < ids.length; i++) {
            if (waiting[i] > 0) {
                unordered.add(ids[i]);
            }
        }
    }

    // ------------------------------------------------------------------ dependencies of one node

    /** The ids the node names as parent and as wall, each once; they need not be in the plan. */
    static Set<String> dependenciesOf(PlanNode n) {
        Set<String> deps = new LinkedHashSet<>();
        if (n.parent() != null) {
            deps.add(n.parent());
        }
        if (n.anchor() instanceof Anchor.OnSurface s) {
            deps.add(s.nodeId());
        }
        return deps;
    }

    private List<String> presentDependencies(PlanNode n) {
        List<String> present = new ArrayList<>();
        for (String dep : dependenciesOf(n)) {
            if (nodes.containsKey(dep)) {
                present.add(dep);
            }
        }
        return present;
    }

    private static Set<String> dependentsIn(Map<String, Set<String>> map, String id) {
        return map.getOrDefault(id, Set.of());
    }

    private static void link(Map<String, Set<String>> map, String id, String dependent) {
        map.computeIfAbsent(id, k -> new HashSet<>()).add(dependent);
    }

    private static void unlink(Map<String, Set<String>> map, String id, String dependent) {
        Set<String> set = map.get(id);
        if (set != null && set.remove(dependent) && set.isEmpty()) {
            map.remove(id);
        }
    }

    // ------------------------------------------------------------------ the loop question

    /** What a bounded walk found, and how many nodes it visited. */
    record Walk(boolean found, int visited) {
    }

    /**
     * Whether {@code targetId} is met by following, from {@code startId}, every node's parent and every node's
     * surface target. Each node is visited once (a set of the visited ids, an explicit stack, no recursion), so the
     * walk is bounded by the number of nodes however long the chains are and even when the plan already holds a loop.
     */
    static Walk walk(Map<String, PlanNode> nodes, String startId, String targetId) {
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.push(startId);
        while (!pending.isEmpty()) {
            String id = pending.pop();
            if (id.equals(targetId)) {
                return new Walk(true, visited.size());
            }
            if (!visited.add(id)) {
                continue;
            }
            PlanNode node = nodes.get(id);
            if (node == null) {
                continue;
            }
            if (node.parent() != null) {
                pending.push(node.parent());
            }
            if (node.anchor() instanceof Anchor.OnSurface surface) {
                pending.push(surface.nodeId());
            }
        }
        return new Walk(false, visited.size());
    }

    /**
     * Whether the existing node {@code nodeId} may rest on the existing wall {@code wallId} without closing a loop.
     * When it may, the labels are already rearranged so that the new dependency agrees with the order; the patcher
     * then records the move with {@link #dependenciesChanged}. When it may not, nothing changes.
     */
    boolean allowsResting(String nodeId, String wallId) {
        if (unordered.contains(wallId) || unordered.contains(nodeId)) {
            if (walk(nodes, wallId, nodeId).found()) {
                return false;
            }
            if (unordered.contains(wallId)) {
                markUnordered(nodeId);
            }
            return true;
        }
        Slot wall = slots.get(wallId);
        Slot node = slots.get(nodeId);
        if (wall.label < node.label) {
            return true;
        }
        if (presentDependencies(nodes.get(wallId)).isEmpty()) {
            wall.label = --lowest;
            return true;
        }
        if (dependentsIn(nodeDependents, nodeId).isEmpty()) {
            node.label = ++highest;
            return true;
        }
        return reorder(node, wall);
    }

    /**
     * The Pearce-Kelly step for "node rests on wall" when label(wall) &gt; label(node): false (and no change) when the
     * forward search from the node reaches the wall, else the two affected sets swap places in the order.
     */
    private boolean reorder(Slot node, Slot wall) {
        long mark = ++searchMark;
        List<Slot> forward = new ArrayList<>();
        ArrayDeque<Slot> pending = new ArrayDeque<>();
        node.mark = mark;
        pending.push(node);
        while (!pending.isEmpty()) {
            Slot s = pending.pop();
            forward.add(s);
            for (String d : dependentsIn(nodeDependents, s.id)) {
                if (d.equals(wall.id)) {
                    return false;
                }
                Slot next = slots.get(d);
                // an unordered dependent (no slot) cannot lead back to the labelled wall: unordered is closed under
                // dependents
                if (next != null && next.label < wall.label && next.mark != mark) {
                    next.mark = mark;
                    pending.push(next);
                }
            }
        }
        List<Slot> backward = new ArrayList<>();
        wall.mark = mark;
        pending.push(wall);
        while (!pending.isEmpty()) {
            Slot s = pending.pop();
            backward.add(s);
            for (String dep : dependenciesOf(nodes.get(s.id))) {
                Slot next = slots.get(dep);
                if (next != null && next.label > node.label && next.mark != mark) {
                    next.mark = mark;
                    pending.push(next);
                }
            }
        }
        forward.sort(BY_LABEL);
        backward.sort(BY_LABEL);
        long[] pool = new long[forward.size() + backward.size()];
        int i = 0;
        for (Slot s : backward) {
            pool[i++] = s.label;
        }
        for (Slot s : forward) {
            pool[i++] = s.label;
        }
        Arrays.sort(pool);
        i = 0;
        for (Slot s : backward) {
            s.label = pool[i++];
        }
        for (Slot s : forward) {
            s.label = pool[i++];
        }
        return true;
    }

    /** Makes the node and everything that depends on it unordered (each visited once). */
    private void markUnordered(String startId) {
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.push(startId);
        while (!pending.isEmpty()) {
            String id = pending.pop();
            if (!unordered.add(id)) {
                continue;
            }
            slots.remove(id);
            for (String d : dependentsIn(nodeDependents, id)) {
                pending.push(d);
            }
        }
    }

    // ------------------------------------------------------------------ the dependents question

    /** The nodes, connections and docks that still name the node, by id in dictionary order: O(answer). */
    Set<String> dependents(String id) {
        Set<String> all = new TreeSet<>(dependentsIn(nodeDependents, id));
        all.addAll(dependentsIn(connectionsNaming, id));
        all.addAll(dependentsIn(docksNaming, id));
        return all;
    }

    // ------------------------------------------------------------------ updates (called after the state changed)

    /**
     * A node was added. It takes the next label; if it depends on an unordered node, or if nodes of a hand-built
     * base plan already named its id (a dangling reference that is now real), it and all that depends on it become
     * unordered instead.
     */
    void nodeAdded(PlanNode node) {
        for (String dep : dependenciesOf(node)) {
            link(nodeDependents, dep, node.id());
        }
        slots.put(node.id(), new Slot(node.id(), ++highest));
        boolean belowUnordered = presentDependencies(node).stream().anyMatch(unordered::contains);
        if (belowUnordered || !dependentsIn(nodeDependents, node.id()).isEmpty()) {
            markUnordered(node.id());
        }
    }

    /** The node's parent or wall changed from {@code before} to {@code after} (a move {@link #allowsResting} allowed). */
    void dependenciesChanged(PlanNode before, PlanNode after) {
        Set<String> old = dependenciesOf(before);
        Set<String> now = dependenciesOf(after);
        for (String dep : old) {
            if (!now.contains(dep)) {
                unlink(nodeDependents, dep, before.id());
            }
        }
        for (String dep : now) {
            if (!old.contains(dep)) {
                link(nodeDependents, dep, after.id());
            }
        }
    }

    /** A node that nothing named any more was removed. */
    void nodeRemoved(PlanNode node) {
        for (String dep : dependenciesOf(node)) {
            unlink(nodeDependents, dep, node.id());
        }
        slots.remove(node.id());
        unordered.remove(node.id());
    }

    private static Set<String> namedBy(Connection c) {
        Set<String> ids = new HashSet<>();
        ids.add(c.from().nodeId());
        ids.add(c.to().nodeId());
        ids.addAll(c.constraints().avoidNodeIds());
        if (c.routing() instanceof Routing.Explicit e) {
            ids.addAll(e.viaNodeIds());
        }
        return ids;
    }

    private static Set<String> namedBy(LogisticsPlan.Dock d) {
        Set<String> ids = new HashSet<>(d.dockingConnectorNodeIds());
        for (PortRef p : d.linkedPorts()) {
            ids.add(p.nodeId());
        }
        return ids;
    }

    void connectionAdded(Connection c) {
        for (String id : namedBy(c)) {
            link(connectionsNaming, id, c.id());
        }
    }

    void connectionRemoved(Connection c) {
        for (String id : namedBy(c)) {
            unlink(connectionsNaming, id, c.id());
        }
    }

    /** The logistics were replaced as a whole: O(size of the new docks). */
    void logisticsSet(LogisticsPlan newLogistics) {
        logistics = newLogistics;
        docksNaming.clear();
        if (newLogistics != null) {
            for (LogisticsPlan.Dock d : newLogistics.docks()) {
                for (String id : namedBy(d)) {
                    link(docksNaming, id, d.id());
                }
            }
        }
    }

    // ------------------------------------------------------------------ test accessors

    /** The order label of the node, or null when it has none (absent, or in or below a loop). Package-private for tests. */
    Long labelOf(String id) {
        Slot slot = slots.get(id);
        return slot == null ? null : slot.label;
    }

    /** The nodes that have no label (in or below a loop). Package-private for tests. */
    Set<String> unorderedNodes() {
        return Set.copyOf(unordered);
    }

    /**
     * Everything that is wrong with the index against the live state, empty when it is right: the reverse maps equal a
     * fresh recount, every node is either labelled or unordered, labels are distinct and within the bounds, and every
     * dependency of a labelled node is labelled and has a smaller label. Package-private for tests; O(plan).
     */
    List<String> invariantViolations() {
        List<String> problems = new ArrayList<>();
        DependencyIndex fresh = new DependencyIndex(nodes, connections, logistics);
        if (!fresh.nodeDependents.equals(nodeDependents)) {
            problems.add("node dependents " + nodeDependents + " but recounted " + fresh.nodeDependents);
        }
        if (!fresh.connectionsNaming.equals(connectionsNaming)) {
            problems.add("connections naming " + connectionsNaming + " but recounted " + fresh.connectionsNaming);
        }
        if (!fresh.docksNaming.equals(docksNaming)) {
            problems.add("docks naming " + docksNaming + " but recounted " + fresh.docksNaming);
        }
        Set<Long> distinct = new HashSet<>();
        for (Slot slot : slots.values()) {
            if (!distinct.add(slot.label)) {
                problems.add("label " + slot.label + " is used twice");
            }
            if (slot.label < lowest || slot.label > highest) {
                problems.add("label " + slot.label + " of " + slot.id + " is outside " + lowest + ".." + highest);
            }
        }
        for (PlanNode n : nodes.values()) {
            Slot slot = slots.get(n.id());
            if ((slot != null) == unordered.contains(n.id())) {
                problems.add(n.id() + " must be either labelled or unordered");
                continue;
            }
            if (slot == null) {
                continue;
            }
            for (String dep : presentDependencies(n)) {
                Slot depSlot = slots.get(dep);
                if (depSlot == null || depSlot.label >= slot.label) {
                    problems.add(n.id() + " (" + slot.label + ") depends on " + dep + " ("
                            + (depSlot == null ? "unordered" : String.valueOf(depSlot.label)) + ")");
                }
            }
        }
        for (String id : slots.keySet()) {
            if (!nodes.containsKey(id)) {
                problems.add("label of a node that is not in the plan: " + id);
            }
        }
        for (String id : unordered) {
            if (!nodes.containsKey(id)) {
                problems.add("unordered node that is not in the plan: " + id);
            }
        }
        return problems;
    }
}
