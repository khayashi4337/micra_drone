package io.github.khayashi4337.micradrone.build.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * The order in which nodes can be ADDED to a plan: a node needs its parent and the wall its {@link Anchor.OnSurface}
 * anchor rests on to be there first. A plan stores its nodes in the order they were added, but a relocation can put a
 * node onto a wall added AFTER it, and a plan read loosely from JSON or a script may list a child before its parent, so
 * the order a list has is not always one it can be built in. This is the ONE implementation of that order, shared by
 * whatever rebuilds a plan from a list of nodes ({@code PlanScriptWriter} writes them, {@code PlanPatcher.normalize}
 * adds them).
 */
public final class NodeOrder {
    /**
     * {@code placed} is every node that can be added, in a stable topological order; {@code stuck} is every node that
     * never can (it is part of a loop through parents and walls, rests on itself, or waits for such a node), in the
     * order of the input list.
     */
    public record Result(List<PlanNode> placed, List<PlanNode> stuck) {
        public Result {
            placed = List.copyOf(placed);
            stuck = List.copyOf(stuck);
        }
    }

    private NodeOrder() {
    }

    /**
     * A STABLE topological order: repeatedly the earliest node (in the list's own order) whose parent and whose wall
     * are already placed, or are not nodes of the list (an id that names no node needs nothing; a repeated id means its
     * first node). A list that is already in dependency order comes out unchanged. Kahn's algorithm with the ready
     * nodes in a queue keyed by list position: O(n log n), no recursion, so a very long chain is fine. The input list
     * is not changed.
     */
    public static Result of(List<PlanNode> nodes) {
        Map<String, Integer> positionOf = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            positionOf.putIfAbsent(nodes.get(i).id(), i);
        }
        int[] missing = new int[nodes.size()];
        List<List<Integer>> waiting = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            waiting.add(new ArrayList<>());
        }
        for (int i = 0; i < nodes.size(); i++) {
            PlanNode n = nodes.get(i);
            Set<Integer> needs = new HashSet<>();
            addNeed(needs, positionOf, n.parent());
            if (n.anchor() instanceof Anchor.OnSurface surface) {
                addNeed(needs, positionOf, surface.nodeId());
            }
            missing[i] = needs.size();
            for (int need : needs) {
                waiting.get(need).add(i);
            }
        }
        PriorityQueue<Integer> ready = new PriorityQueue<>();
        for (int i = 0; i < nodes.size(); i++) {
            if (missing[i] == 0) {
                ready.add(i);
            }
        }
        List<PlanNode> placed = new ArrayList<>(nodes.size());
        boolean[] isPlaced = new boolean[nodes.size()];
        while (!ready.isEmpty()) {
            int next = ready.poll();
            placed.add(nodes.get(next));
            isPlaced[next] = true;
            for (int dependent : waiting.get(next)) {
                missing[dependent]--;
                if (missing[dependent] == 0) {
                    ready.add(dependent);
                }
            }
        }
        List<PlanNode> stuck = new ArrayList<>();
        if (placed.size() < nodes.size()) {
            for (int i = 0; i < nodes.size(); i++) {
                if (!isPlaced[i]) {
                    stuck.add(nodes.get(i));
                }
            }
        }
        return new Result(placed, stuck);
    }

    /** Adds the position of the node {@code id} to {@code needs} when the list has such a node (a null id needs nothing). */
    private static void addNeed(Set<Integer> needs, Map<String, Integer> positionOf, String id) {
        Integer position = id == null ? null : positionOf.get(id);
        if (position != null) {
            needs.add(position);
        }
    }
}
