package io.github.khayashi4337.micradrone.build.plan;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The local origin of every node, found by walking parents: an Absolute position is relative to the parent's
 * origin (or the plan origin). OnSurface nodes depend on the wall's geometry, so they are resolved by the compiler.
 * The nodes may come from a plan that never passed the patcher, so a parent loop, a missing parent and an
 * oversized position are reported as E-ANCHOR instead of being followed.
 */
public final class Origins {
    /** Twice the per-node limit: a few levels of nesting still fit, a wrapped-around int never does. */
    private static final long SUM_LIMIT = 2L * PlanPatcher.MAX_COORD;
    /** The origin a node without a parent is relative to. */
    static final LocalPos PLAN_ORIGIN = new LocalPos(0, 0, 0);

    // Keys that tell apart the E-ANCHOR issues of one node. The first two are also used by PlanExpander.
    static final String KEY_ANCHOR = "anchor";
    static final String KEY_PARENT = "parent";
    private static final String KEY_CYCLE = "cycle";
    private static final String KEY_SLOT = "slot";

    private Origins() {
    }

    /**
     * The origin of each node that has one, by node id. A node has none when it sits on a wall face, hangs below such
     * a node, or when an issue was added for it (or for an ancestor); each problem is reported once.
     */
    public static Map<String, LocalPos> resolve(List<PlanNode> nodes, SlotResolver slots, List<Issue> issues) {
        return resolve(nodes, slots, issues, Set.of());
    }

    /**
     * As above, for a caller that already refused (and reported) the nodes in {@code refusedIds} and left them out of
     * {@code nodes}: a node whose parent is one of them has no origin and is not reported as having a missing parent.
     */
    public static Map<String, LocalPos> resolve(List<PlanNode> nodes, SlotResolver slots, List<Issue> issues, Set<String> refusedIds) {
        Walk walk = new Walk(nodes, slots, issues, refusedIds);
        for (PlanNode n : nodes) {
            walk.resolve(walk.node(n.id()));
        }
        return walk.origins;
    }

    /** The sum of two positions, or null when it is outside what a plan may hold. Added in long, so it cannot wrap. */
    static LocalPos sum(LocalPos a, LocalPos b) {
        long u = (long) a.u() + b.u();
        long v = (long) a.v() + b.v();
        long w = (long) a.w() + b.w();
        if (Math.abs(u) > SUM_LIMIT || Math.abs(v) > SUM_LIMIT || Math.abs(w) > SUM_LIMIT) {
            return null;
        }
        return new LocalPos((int) u, (int) v, (int) w);
    }

    static Issue positionTooLarge(String nodeId) {
        return Issue.of(IssueCode.E_ANCHOR, KEY_ANCHOR, List.of(nodeId), "位置が大きすぎます");
    }

    /** The slot's position; empty after adding E-ANCHOR for {@code nodeId} when the slot does not resolve. */
    static Optional<LocalPos> resolveSlot(SlotResolver slots, String nodeId, String slotId, List<Issue> issues) {
        Optional<LocalPos> pos = slots.resolve(slotId);
        if (pos.isEmpty()) {
            issues.add(Issue.of(IssueCode.E_ANCHOR, KEY_SLOT, List.of(nodeId),
                    "スロット" + slotId + "を解決できません(スロットは建屋の意味解析が載るP6以降で使えます)"));
        }
        return pos;
    }

    /** One run over a list of nodes: the origins found so far, and the nodes known to have none. */
    private static final class Walk {
        /** A repeated id keeps its last node, so every lookup of an id agrees. */
        private final Map<String, PlanNode> byId = new HashMap<>();
        private final SlotResolver slots;
        private final List<Issue> issues;
        final Map<String, LocalPos> origins = new HashMap<>();
        private final Set<String> unresolved = new HashSet<>();
        /** Ids of nodes the caller refused and reported: a parent named here is not "missing". */
        private final Set<String> refused;

        Walk(List<PlanNode> nodes, SlotResolver slots, List<Issue> issues, Set<String> refused) {
            for (PlanNode n : nodes) {
                byId.put(n.id(), n);
            }
            this.slots = slots;
            this.issues = issues;
            this.refused = refused;
        }

        PlanNode node(String id) {
            return byId.get(id);
        }

        /**
         * Resolves the node and the ancestors above it that are still open. The parents are climbed first (each node
         * once, so the climb is bounded by the node count and a loop is met as a node seen twice), then resolved from
         * the top down. Nothing recurses, so a very long chain cannot exhaust the stack.
         */
        void resolve(PlanNode start) {
            if (origins.containsKey(start.id()) || unresolved.contains(start.id())) {
                return;
            }
            List<PlanNode> chain = new ArrayList<>();
            Set<String> onChain = new HashSet<>();
            LocalPos base = PLAN_ORIGIN;
            PlanNode current = start;
            while (true) {
                chain.add(current);
                onChain.add(current.id());
                String parentId = current.parent();
                if (parentId == null) {
                    break;
                }
                PlanNode parent = byId.get(parentId);
                if (parent == null) {
                    // a parent that was refused is already reported; only one that is simply not there is a new problem
                    abandon(chain, refused.contains(parentId) ? null : Issue.of(IssueCode.E_ANCHOR, KEY_PARENT,
                            List.of(current.id()), "親のノードがありません: " + parentId));
                    return;
                }
                LocalPos parentOrigin = origins.get(parentId);
                if (parentOrigin != null) {
                    base = parentOrigin;
                    break;
                }
                if (unresolved.contains(parentId)) {
                    abandon(chain, null);
                    return;
                }
                if (onChain.contains(parentId)) {
                    abandon(chain, Issue.of(IssueCode.E_ANCHOR, KEY_CYCLE, List.of(parentId), "親子の関係が輪になっています"));
                    return;
                }
                current = parent;
            }
            for (int i = chain.size() - 1; i >= 0; i--) {
                PlanNode n = chain.get(i);
                LocalPos pos = place(n, base);
                if (pos == null) {
                    // n and the nodes below it in the chain cannot be resolved; the reason, if any, is already reported
                    abandon(chain.subList(0, i + 1), null);
                    return;
                }
                origins.put(n.id(), pos);
                base = pos;
            }
        }

        /** Marks the nodes as having no origin, after adding {@code reason} when there is one. */
        private void abandon(List<PlanNode> nodes, Issue reason) {
            if (reason != null) {
                issues.add(reason);
            }
            for (PlanNode n : nodes) {
                unresolved.add(n.id());
            }
        }

        /** The node's origin given its parent's, or null: silently for a wall face, with an issue otherwise. */
        private LocalPos place(PlanNode n, LocalPos base) {
            return switch (n.anchor()) {
                case Anchor.Absolute a -> offset(n, base, a.pos());
                case Anchor.InSlot s -> resolveSlot(slots, n.id(), s.slotId(), issues).orElse(null);
                case Anchor.OnSurface surface -> null;
            };
        }

        private LocalPos offset(PlanNode n, LocalPos base, LocalPos relative) {
            LocalPos pos = sum(base, relative);
            if (pos == null) {
                issues.add(positionTooLarge(n.id()));
            }
            return pos;
        }
    }
}
