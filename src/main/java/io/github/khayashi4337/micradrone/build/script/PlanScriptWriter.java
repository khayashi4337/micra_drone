package io.github.khayashi4337.micradrone.build.script;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Constraints;
import io.github.khayashi4337.micradrone.build.model.Dir6;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.LogisticsPlan;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.PortRef;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.Routing;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Site;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.lang.CommandNames;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;

/**
 * Writes a plan as construction scripts a person can read and edit: one statement per line, one call per plan
 * operation. Feeding the scripts to {@link PlanScriptRunner} and applying the recorded patch to an empty plan
 * gives back a plan with the same content hash. Plans that do not fit one script are cut into several numbered
 * scripts which must be run in order (nodes are written after their parent and after the wall they rest on).
 */
public final class PlanScriptWriter {
    /**
     * Same value as DroneControllerBlockEntity.MAX_SCRIPT_CHARS, repeated here so this Minecraft-free core does not depend
     * on that class; a test compares the two source texts.
     */
    public static final int MAX_SCRIPT_CHARS = 10_000;

    /**
     * Room kept for the {@code # 建設スクリプト i/n(計画 <id>)} header line when statements are packed. The fixed
     * header text, the i/n counters and a plan id cut to {@link #PLAN_ID_COMMENT_CHARS} need about 70 characters,
     * so 120 covers the header with margin. Package-private so the packing-boundary tests can derive budgets.
     */
    static final int HEADER_RESERVE = 120;

    /** The plan id in the header comment is cut to this many characters (newline and CR become spaces). */
    private static final int PLAN_ID_COMMENT_CHARS = 40;

    /** How much of an over-long statement is quoted in the IllegalStateException message. */
    private static final int OVERFLOW_PREVIEW_CHARS = 60;

    /** How many node ids the message of a plan that cannot be ordered lists (the rest is summarised). */
    private static final int STUCK_NODES_LISTED = 10;

    private PlanScriptWriter() {
    }

    /** The statements packed into scripts of at most {@link #MAX_SCRIPT_CHARS} characters each. */
    public static List<String> write(SemanticPlan plan) {
        return write(plan, MAX_SCRIPT_CHARS);
    }

    /**
     * The statements packed into scripts whose header plus lines fit in {@code maxChars} characters.
     * A statement that alone needs more than a script may hold is refused, never cut in half.
     */
    public static List<String> write(SemanticPlan plan, int maxChars) {
        return pack(plan.planId(), statements(plan), maxChars);
    }

    /**
     * One statement per plan element, in the order the runner must see them: the site, the style entries,
     * the mood tags, the nodes in dependency order (see {@link #inDependencyOrder}), the connections,
     * then the logistics.
     */
    private static List<String> statements(SemanticPlan plan) {
        List<String> out = new ArrayList<>();
        if (plan.site() != null) {
            out.add(siteLine(plan.site()));
        }
        for (Map.Entry<String, String> e : plan.style().palette().entrySet()) {
            out.add("style(" + q(e.getKey()) + ", " + q(e.getValue()) + ")");
        }
        for (String mood : plan.style().moodTags()) {
            out.add("mood(" + q(mood) + ")");
        }
        for (PlanNode n : inDependencyOrder(plan.nodes())) {
            out.add(nodeLine(n));
        }
        for (Connection c : plan.connections()) {
            out.add(connectLine(c));
        }
        if (plan.logistics() != null) {
            out.add(logisticsLine(plan.logistics()));
        }
        return out;
    }

    /**
     * The nodes in a STABLE topological order: repeatedly the earliest node (in the plan's own order) whose parent and
     * whose {@code OnSurface} wall are already written, or are not nodes of the plan. A plan stores nodes in the
     * order they were added, and a relocation can put a node onto a wall added AFTER it, so the plan's own order is
     * not always one a script can be replayed in (the patcher refuses a node whose wall does not exist yet). A plan
     * that is already in dependency order comes out unchanged, statement for statement. The order does not touch the
     * content hash, which sorts nodes by id. Kahn's algorithm with the ready nodes in a queue keyed by plan
     * position: O(n log n), no recursion. The plan model refuses loops through parents and walls, so this always
     * finishes; a hand-built plan that breaks that rule gets an {@link IllegalStateException} naming the nodes
     * that could not be placed.
     */
    private static List<PlanNode> inDependencyOrder(List<PlanNode> nodes) {
        Map<String, Integer> positionOf = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            positionOf.put(nodes.get(i).id(), i);
        }
        int[] missing = new int[nodes.size()];
        List<List<Integer>> waiting = new ArrayList<>();
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
        List<PlanNode> ordered = new ArrayList<>(nodes.size());
        boolean[] written = new boolean[nodes.size()];
        while (!ready.isEmpty()) {
            int next = ready.poll();
            ordered.add(nodes.get(next));
            written[next] = true;
            for (int dependent : waiting.get(next)) {
                missing[dependent]--;
                if (missing[dependent] == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (ordered.size() < nodes.size()) {
            List<String> stuck = new ArrayList<>();
            for (int i = 0; i < nodes.size(); i++) {
                if (!written[i]) {
                    stuck.add(nodes.get(i).id());
                }
            }
            throw new IllegalStateException("nodes that depend on each other in a loop cannot be written in any order a script can replay ("
                    + stuck.size() + " of " + nodes.size() + " stuck): "
                    + String.join(", ", stuck.subList(0, Math.min(STUCK_NODES_LISTED, stuck.size())))
                    + (stuck.size() > STUCK_NODES_LISTED ? ", ..." : ""));
        }
        return ordered;
    }

    /** Adds the position of the node {@code id} to {@code needs} when the plan has such a node (a null id needs nothing). */
    private static void addNeed(Set<Integer> needs, Map<String, Integer> positionOf, String id) {
        Integer position = id == null ? null : positionOf.get(id);
        if (position != null) {
            needs.add(position);
        }
    }

    /** Greedy packing: statements go into the current script while they fit; an over-long one is refused. */
    private static List<String> pack(String planId, List<String> statements, int maxChars) {
        int budget = maxChars - HEADER_RESERVE;
        List<List<String>> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int size = 0;
        for (String s : statements) {
            int add = s.length() + 1; // the statement plus its newline
            if (add > budget) {
                throw new IllegalStateException("a statement is longer than a script may hold ("
                        + add + " > " + budget + " characters): "
                        + s.substring(0, Math.min(OVERFLOW_PREVIEW_CHARS, s.length())) + "...");
            }
            if (size + add > budget && !current.isEmpty()) {
                chunks.add(current);
                current = new ArrayList<>();
                size = 0;
            }
            current.add(s);
            size += add;
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        if (chunks.isEmpty()) {
            chunks.add(List.of());
        }
        List<String> scripts = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            StringBuilder sb = new StringBuilder();
            sb.append("# 建設スクリプト ").append(i + 1).append('/').append(chunks.size())
                    .append("(計画 ").append(headerPlanId(planId)).append(")\n");
            for (String s : chunks.get(i)) {
                sb.append(s).append('\n');
            }
            scripts.add(sb.toString());
        }
        return scripts;
    }

    /** The plan id as a one-line header comment: CR and LF become spaces, then it is cut to the comment length. */
    private static String headerPlanId(String planId) {
        String s = planId.replace('\n', ' ').replace('\r', ' ');
        return s.length() <= PLAN_ID_COMMENT_CHARS ? s : s.substring(0, PLAN_ID_COMMENT_CHARS);
    }

    private static String siteLine(Site s) {
        StringBuilder sb = new StringBuilder();
        sb.append("site(").append(q(s.dimension())).append(", ")
                .append(s.frame().origin().x()).append(", ").append(s.frame().origin().y()).append(", ").append(s.frame().origin().z())
                .append(", ").append(q(s.frame().facing().lower())).append(", ").append(box(s.localBounds()));
        if (!s.terrainDigest().isEmpty() || !s.claimId().isEmpty()) {
            sb.append(", ").append(q(s.terrainDigest())).append(", ").append(q(s.claimId()));
        }
        return sb.append(')').toString();
    }

    /**
     * {@code micra:*} parts become their own command (pillar(...)), everything else the generic part(id, type, ...)
     * form. Tags come out only when tags or a label exist, the label only when it is not empty.
     */
    private static String nodeLine(PlanNode n) {
        boolean sugar = n.type().startsWith(BuildingParts.ID_PREFIX)
                && CommandNames.PLAN_PART_COMMANDS.contains(n.type().substring(BuildingParts.ID_PREFIX.length()));
        StringBuilder sb = new StringBuilder();
        if (sugar) {
            sb.append(n.type(), BuildingParts.ID_PREFIX.length(), n.type().length()).append('(').append(q(n.id()));
        } else {
            sb.append("part(").append(q(n.id())).append(", ").append(q(n.type()));
        }
        sb.append(", ").append(n.parent() == null ? "None" : q(n.parent()))
                .append(", ").append(anchor(n.anchor()))
                .append(", ").append(params(n));
        if (!n.tags().isEmpty() || !n.label().isEmpty()) {
            sb.append(", ").append(list(n.tags()));
            if (!n.label().isEmpty()) {
                sb.append(", ").append(q(n.label()));
            }
        }
        return sb.append(')').toString();
    }

    private static String anchor(Anchor a) {
        return switch (a) {
            case Anchor.Absolute abs -> {
                String s = "[" + abs.pos().u() + ", " + abs.pos().v() + ", " + abs.pos().w();
                if (!Rot.NONE.equals(abs.rot())) {
                    s += ", " + abs.rot().quarterTurns() + ", " + (abs.rot().mirror() ? "True" : "False");
                }
                yield s + "]";
            }
            case Anchor.OnSurface s -> "[\"surface\", " + q(s.nodeId()) + ", " + q(s.side().lower())
                    + ", " + s.u() + ", " + s.v() + "]";
            case Anchor.InSlot s -> "[\"slot\", " + q(s.slotId()) + ", " + s.rot().quarterTurns()
                    + ", " + (s.rot().mirror() ? "True" : "False") + "]";
        };
    }

    /** The parameter dict in key order (the node's params are already a sorted map). */
    private static String params(PlanNode n) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ParamValue> e : n.params().entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(q(e.getKey())).append(": ").append(param(e.getValue()));
            first = false;
        }
        return sb.append('}').toString();
    }

    /**
     * A parameter value in script literal form. Numbers are written as plain decimals (the language has no
     * exponent form): an integral double loses its trailing zeros, so -0.0 writes as "0" and comes back as
     * positive zero - the model's NumV cannot keep the sign of zero through text.
     */
    private static String param(ParamValue v) {
        return switch (v) {
            case ParamValue.IntV i -> String.valueOf(i.value());
            case ParamValue.NumV d -> number(d.value());
            case ParamValue.BoolV b -> b.value() ? "True" : "False";
            case ParamValue.StrV s -> q(s.value());
            case ParamValue.EnumV e -> q(e.value());
            case ParamValue.MaterialV m -> q(m.value());
            case ParamValue.ListV l -> {
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (ParamValue item : l.value()) {
                    if (!first) {
                        sb.append(", ");
                    }
                    sb.append(param(item));
                    first = false;
                }
                yield sb.append(']').toString();
            }
        };
    }

    /** A double as plain decimal text; integral values lose the fraction, so 2.0 writes as "2" and -0.0 as "0". */
    private static String number(double d) {
        if (d == 0.0) {
            return "0";
        }
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    /**
     * connect(id, "node.port", "node.port", "kind"): routing and constraints are appended only when present -
     * Auto uses None, Explicit a via list (possibly empty), and the constraint dict only the populated keys.
     */
    private static String connectLine(Connection c) {
        StringBuilder sb = new StringBuilder("connect(").append(q(c.id())).append(", ")
                .append(q(c.from().nodeId() + "." + c.from().port())).append(", ")
                .append(q(c.to().nodeId() + "." + c.to().port())).append(", ")
                .append(q(c.kind().lower()));
        boolean explicit = c.routing() instanceof Routing.Explicit;
        boolean constraints = !Constraints.NONE.equals(c.constraints());
        if (explicit || constraints) {
            sb.append(", ").append(switch (c.routing()) {
                case Routing.Auto ignored -> "None";
                case Routing.Explicit e -> list(e.viaNodeIds());
            });
        }
        if (constraints) {
            sb.append(", ").append(constraints(c.constraints()));
        }
        return sb.append(')').toString();
    }

    /** The populated keys only, in dictionary order: avoid, entry_dirs, max_length, max_turns. */
    private static String constraints(Constraints c) {
        Map<String, String> parts = new TreeMap<>();
        if (!c.avoidNodeIds().isEmpty()) {
            parts.put("avoid", list(c.avoidNodeIds()));
        }
        if (!c.allowedEntryDirs().isEmpty()) {
            List<String> dirs = new ArrayList<>();
            for (Dir6 d : c.allowedEntryDirs()) {
                dirs.add(d.lower());
            }
            parts.put("entry_dirs", list(dirs));
        }
        if (c.maxLength() != null) {
            parts.put("max_length", String.valueOf(c.maxLength()));
        }
        if (c.maxTurns() != null) {
            parts.put("max_turns", String.valueOf(c.maxTurns()));
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : parts.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(q(e.getKey())).append(": ").append(e.getValue());
            first = false;
        }
        return sb.append('}').toString();
    }

    /** logistics([{dock}...], [{route}...], [{flow}...]) - the dict keys the recorder reads. */
    private static String logisticsLine(LogisticsPlan l) {
        StringBuilder sb = new StringBuilder("logistics([");
        boolean first = true;
        for (LogisticsPlan.Dock d : l.docks()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(dock(d));
            first = false;
        }
        sb.append("], [");
        first = true;
        for (LogisticsPlan.Route r : l.routes()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(route(r));
            first = false;
        }
        sb.append("], [");
        first = true;
        for (LogisticsPlan.CargoFlow f : l.flows()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(flow(f));
            first = false;
        }
        return sb.append("])").toString();
    }

    private static String dock(LogisticsPlan.Dock d) {
        Map<String, String> parts = new TreeMap<>();
        parts.put("approach", q(d.approach().lower()));
        parts.put("clearance", box(d.clearanceBox()));
        parts.put("connectors", list(d.dockingConnectorNodeIds()));
        parts.put("id", q(d.id()));
        parts.put("pad", box(d.padBox()));
        parts.put("ports", portList(d.linkedPorts()));
        return dict(parts);
    }

    private static String route(LogisticsPlan.Route r) {
        Map<String, String> parts = new TreeMap<>();
        parts.put("airship", r.airshipTemplateId() == null ? "None" : q(r.airshipTemplateId()));
        parts.put("from", q(r.fromDock()));
        parts.put("id", q(r.id()));
        parts.put("to", q(r.toDock()));
        StringBuilder waypoints = new StringBuilder("[");
        boolean first = true;
        for (LocalPos p : r.waypoints()) {
            if (!first) {
                waypoints.append(", ");
            }
            waypoints.append("[").append(p.u()).append(", ").append(p.v()).append(", ").append(p.w()).append(']');
            first = false;
        }
        parts.put("waypoints", waypoints.append(']').toString());
        return dict(parts);
    }

    private static String flow(LogisticsPlan.CargoFlow f) {
        Map<String, String> parts = new TreeMap<>();
        parts.put("from", q(f.fromDock()));
        parts.put("item", q(f.itemId()));
        parts.put("per_min", number(f.perMin()));
        parts.put("to", q(f.toDock()));
        return dict(parts);
    }

    /** A dict from pre-rendered value text, keys in dictionary order. */
    private static String dict(Map<String, String> parts) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : parts.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(q(e.getKey())).append(": ").append(e.getValue());
            first = false;
        }
        return sb.append('}').toString();
    }

    private static String portList(List<PortRef> ports) {
        List<String> out = new ArrayList<>();
        for (PortRef p : ports) {
            out.add(p.nodeId() + "." + p.port());
        }
        return list(out);
    }

    private static String box(Box b) {
        return "[" + b.minA() + ", " + b.minB() + ", " + b.minC() + ", " + b.maxA() + ", " + b.maxB() + ", " + b.maxC() + "]";
    }

    /** A list of quoted strings: {@code ["a", "b"]} - empty is {@code []}. */
    private static String list(Iterable<String> items) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String s : items) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(q(s));
            first = false;
        }
        return sb.append(']').toString();
    }

    /**
     * A quoted string literal: backslash, double quote, newline and tab are escaped - those are the escapes the
     * lexer understands. Every other character goes through unchanged, including control characters the plan
     * itself would refuse (the script layer carries text verbatim; refusing them is the patcher's job).
     */
    static String q(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
