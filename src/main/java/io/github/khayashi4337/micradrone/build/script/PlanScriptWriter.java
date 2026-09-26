package io.github.khayashi4337.micradrone.build.script;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Constraints;
import io.github.khayashi4337.micradrone.build.model.Dir6;
import io.github.khayashi4337.micradrone.build.model.LogisticsPlan;
import io.github.khayashi4337.micradrone.build.model.NodeOrder;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.PortRef;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.Routing;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Site;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.lang.CommandNames;
import io.github.khayashi4337.micradrone.lang.MicraNone;
import io.github.khayashi4337.micradrone.lang.PlanAnchorArgs;
import io.github.khayashi4337.micradrone.lang.PlanApi;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes a plan as construction scripts a person can read and edit: one statement per line, one call per plan
 * operation. Feeding the scripts to {@link PlanScriptRunner} and applying the recorded patch to an empty plan
 * gives back the same plan (the same content hash; the same nodes, though a plan whose stored order is not a
 * dependency order comes back with its nodes reordered). Plans that do not fit one script are cut into several
 * numbered scripts which must be run in order (nodes are written after their parent and after the wall they rest on).
 *
 * <p>What the round trip does not promise. The writer refuses, with an exception, only what it cannot write: one
 * statement longer than a script may hold (it is refused, never split; a {@code logistics(...)} call is ONE
 * statement, so the docks, routes and flows of a plan must fit in a single script together), a script limit that
 * cannot hold the header, and nodes that depend on each other in a loop through parents and walls (a plan the
 * patcher never builds; an {@link IllegalStateException}). It does NOT check the run-wide budgets of the
 * {@link PlanRecorder} that reads the scripts back: at most 200,000 recorded elements and 1,000,000 recorded
 * characters over a whole run. A plan beyond them is written without complaint and then refused by the runner as a
 * batch (E-SCRIPT-LIMIT). Examples, measured on this writer's output (they move if the recorder budgets or the
 * writer's spelling change): about 56,000 small nodes (56,172 pillars with ids of up to 6 characters run, 56,173 do
 * not); about 100 nodes with labels of 9,800 characters (101 run, 102 do not); about 3,000 floors with the maximum 64
 * hole values (3,030 run, 3,031 do not; with 32 hole values each, 5,882 run and 5,883 do not).
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

    // The literal forms of the script language.
    private static final String ITEM_SEPARATOR = ", ";
    private static final String KEY_VALUE_SEPARATOR = ": ";
    private static final String NONE = MicraNone.INSTANCE.toString();
    private static final String TRUE = "True";
    private static final String FALSE = "False";

    private PlanScriptWriter() {
    }

    /** The statements packed into scripts of at most {@link #MAX_SCRIPT_CHARS} characters each. */
    public static List<String> write(SemanticPlan plan) {
        return write(plan, MAX_SCRIPT_CHARS);
    }

    /**
     * The statements packed into scripts whose header plus lines fit in {@code maxChars} characters.
     * A statement that alone needs more than a script may hold is refused, never cut in half; so is a
     * {@code maxChars} that cannot hold even the header ({@link #HEADER_RESERVE} characters are kept for it).
     *
     * @throws IllegalArgumentException when {@code maxChars} is not above {@link #HEADER_RESERVE}
     * @throws IllegalStateException when one statement is longer than a script may hold, or the nodes depend on
     *     each other in a loop (a plan the patcher would never have built)
     */
    public static List<String> write(SemanticPlan plan, int maxChars) {
        if (maxChars <= HEADER_RESERVE) {
            throw new IllegalArgumentException("a script limit of " + maxChars
                    + " characters cannot hold the header: it must be above " + HEADER_RESERVE);
        }
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
            out.add(call(CommandNames.STYLE, q(e.getKey()), q(e.getValue())));
        }
        for (String mood : plan.style().moodTags()) {
            out.add(call(CommandNames.MOOD, q(mood)));
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
     * The nodes in the stable dependency order of {@link NodeOrder}: a node comes after its parent and after the wall
     * it rests on. A plan stores nodes in the order they were added, and a relocation can put a node onto a wall added
     * AFTER it, so the plan's own order is not always one a script can be replayed in (the patcher refuses a node whose
     * wall does not exist yet). A plan that is already in dependency order comes out unchanged, statement for
     * statement. The order does not touch the content hash, which sorts nodes by id. The plan model refuses loops
     * through parents and walls, so nothing is ever stuck; a hand-built plan that breaks that rule gets an
     * {@link IllegalStateException} naming the nodes that could not be placed.
     */
    private static List<PlanNode> inDependencyOrder(List<PlanNode> nodes) {
        NodeOrder.Result order = NodeOrder.of(nodes);
        List<PlanNode> stuck = order.stuck();
        if (!stuck.isEmpty()) {
            List<String> stuckIds = stuck.stream().map(PlanNode::id).toList();
            throw new IllegalStateException("nodes that depend on each other in a loop cannot be written in any order a script can replay ("
                    + stuckIds.size() + " of " + nodes.size() + " stuck): "
                    + String.join(ITEM_SEPARATOR, stuckIds.subList(0, Math.min(STUCK_NODES_LISTED, stuckIds.size())))
                    + (stuckIds.size() > STUCK_NODES_LISTED ? ITEM_SEPARATOR + "..." : ""));
        }
        return order.placed();
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
            StringBuilder sb = new StringBuilder(header(i + 1, chunks.size(), planId));
            for (String s : chunks.get(i)) {
                sb.append(s).append('\n');
            }
            scripts.add(sb.toString());
        }
        return scripts;
    }

    private static String header(int index, int count, String planId) {
        return "# 建設スクリプト " + index + '/' + count + "(計画 " + headerPlanId(planId) + ")\n";
    }

    /**
     * The plan id as a one-line header comment: CR and LF become spaces, then it is cut to the comment length -
     * never in the middle of a surrogate pair, which would leave a lone surrogate that is not valid text.
     */
    private static String headerPlanId(String planId) {
        String oneLine = planId.replace('\n', ' ').replace('\r', ' ');
        if (oneLine.length() <= PLAN_ID_COMMENT_CHARS) {
            return oneLine;
        }
        int end = PLAN_ID_COMMENT_CHARS;
        if (Character.isHighSurrogate(oneLine.charAt(end - 1)) && Character.isLowSurrogate(oneLine.charAt(end))) {
            end--;
        }
        return oneLine.substring(0, end);
    }

    private static String siteLine(Site s) {
        List<String> args = new ArrayList<>(List.of(q(s.dimension()),
                String.valueOf(s.frame().origin().x()), String.valueOf(s.frame().origin().y()), String.valueOf(s.frame().origin().z()),
                q(s.frame().facing().lower()), box(s.localBounds())));
        if (!s.terrainDigest().isEmpty() || !s.claimId().isEmpty()) {
            args.add(q(s.terrainDigest()));
            args.add(q(s.claimId()));
        }
        return call(CommandNames.SITE, args);
    }

    /**
     * {@code micra:*} parts become their own command (pillar(...)), everything else the generic part(id, type, ...)
     * form. Tags come out only when tags or a label exist, the label only when it is not empty.
     */
    private static String nodeLine(PlanNode n) {
        boolean sugar = n.type().startsWith(BuildingParts.ID_PREFIX)
                && CommandNames.PLAN_PART_COMMANDS.contains(n.type().substring(BuildingParts.ID_PREFIX.length()));
        List<String> args = new ArrayList<>();
        args.add(q(n.id()));
        if (!sugar) {
            args.add(q(n.type()));
        }
        args.add(n.parent() == null ? NONE : q(n.parent()));
        args.add(anchor(n.anchor()));
        args.add(params(n));
        if (!n.tags().isEmpty() || !n.label().isEmpty()) {
            args.add(list(n.tags()));
            if (!n.label().isEmpty()) {
                args.add(q(n.label()));
            }
        }
        return call(sugar ? n.type().substring(BuildingParts.ID_PREFIX.length()) : CommandNames.PART, args);
    }

    private static String anchor(Anchor a) {
        return switch (a) {
            case Anchor.Absolute abs -> {
                List<String> items = new ArrayList<>(List.of(String.valueOf(abs.pos().u()), String.valueOf(abs.pos().v()),
                        String.valueOf(abs.pos().w())));
                if (!Rot.NONE.equals(abs.rot())) {
                    items.add(String.valueOf(abs.rot().quarterTurns()));
                    items.add(bool(abs.rot().mirror()));
                }
                yield bracketed(items);
            }
            case Anchor.OnSurface s -> bracketed(List.of(q(PlanAnchorArgs.SURFACE_TEXT), q(s.nodeId()), q(s.side().lower()),
                    String.valueOf(s.u()), String.valueOf(s.v())));
            case Anchor.InSlot s -> bracketed(List.of(q(PlanAnchorArgs.SLOT_TEXT), q(s.slotId()),
                    String.valueOf(s.rot().quarterTurns()), bool(s.rot().mirror())));
        };
    }

    /** The parameter dict in key order (the node's params are already a sorted map). */
    private static String params(PlanNode n) {
        Map<String, String> parts = new TreeMap<>();
        for (Map.Entry<String, ParamValue> e : n.params().entrySet()) {
            parts.put(e.getKey(), param(e.getValue()));
        }
        return dict(parts);
    }

    /**
     * A parameter value in script literal form. Numbers are written as plain decimals (the language has no
     * exponent form): an integral double loses its trailing zeros, so 2.0 writes as "2". The sign of a zero cannot
     * be written, which is why the plan model never stores -0.0.
     */
    private static String param(ParamValue v) {
        return switch (v) {
            case ParamValue.IntV i -> String.valueOf(i.value());
            case ParamValue.NumV d -> number(d.value());
            case ParamValue.BoolV b -> bool(b.value());
            case ParamValue.StrV s -> q(s.value());
            case ParamValue.EnumV e -> q(e.value());
            case ParamValue.MaterialV m -> q(m.value());
            case ParamValue.ListV l -> bracketed(l.value().stream().map(PlanScriptWriter::param).toList());
        };
    }

    /** A double as plain decimal text; integral values lose the fraction, so 2.0 writes as "2" (and any zero as "0"). */
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
        List<String> args = new ArrayList<>(List.of(q(c.id()), q(portText(c.from())), q(portText(c.to())), q(c.kind().lower())));
        boolean explicit = c.routing() instanceof Routing.Explicit;
        boolean constraints = !Constraints.NONE.equals(c.constraints());
        if (explicit || constraints) {
            args.add(switch (c.routing()) {
                case Routing.Auto ignored -> NONE;
                case Routing.Explicit e -> list(e.viaNodeIds());
            });
        }
        if (constraints) {
            args.add(constraints(c.constraints()));
        }
        return call(CommandNames.CONNECT, args);
    }

    /** The populated keys only, in dictionary order: avoid, entry_dirs, max_length, max_turns. */
    private static String constraints(Constraints c) {
        Map<String, String> parts = new TreeMap<>();
        if (!c.avoidNodeIds().isEmpty()) {
            parts.put(PlanScriptKeys.CONSTRAINT_AVOID, list(c.avoidNodeIds()));
        }
        if (!c.allowedEntryDirs().isEmpty()) {
            List<String> dirs = new ArrayList<>();
            for (Dir6 d : c.allowedEntryDirs()) {
                dirs.add(d.lower());
            }
            parts.put(PlanScriptKeys.CONSTRAINT_ENTRY_DIRS, list(dirs));
        }
        if (c.maxLength() != null) {
            parts.put(PlanScriptKeys.CONSTRAINT_MAX_LENGTH, String.valueOf(c.maxLength()));
        }
        if (c.maxTurns() != null) {
            parts.put(PlanScriptKeys.CONSTRAINT_MAX_TURNS, String.valueOf(c.maxTurns()));
        }
        return dict(parts);
    }

    /** logistics([{dock}...], [{route}...], [{flow}...]) - the dict keys the recorder reads. */
    private static String logisticsLine(LogisticsPlan l) {
        return call(CommandNames.LOGISTICS,
                bracketed(l.docks().stream().map(PlanScriptWriter::dock).toList()),
                bracketed(l.routes().stream().map(PlanScriptWriter::route).toList()),
                bracketed(l.flows().stream().map(PlanScriptWriter::flow).toList()));
    }

    private static String dock(LogisticsPlan.Dock d) {
        Map<String, String> parts = new TreeMap<>();
        parts.put(PlanScriptKeys.DOCK_APPROACH, q(d.approach().lower()));
        parts.put(PlanScriptKeys.DOCK_CLEARANCE, box(d.clearanceBox()));
        parts.put(PlanScriptKeys.DOCK_CONNECTORS, list(d.dockingConnectorNodeIds()));
        parts.put(PlanScriptKeys.DOCK_ID, q(d.id()));
        parts.put(PlanScriptKeys.DOCK_PAD, box(d.padBox()));
        parts.put(PlanScriptKeys.DOCK_PORTS, list(d.linkedPorts().stream().map(PlanScriptWriter::portText).toList()));
        return dict(parts);
    }

    private static String route(LogisticsPlan.Route r) {
        Map<String, String> parts = new TreeMap<>();
        parts.put(PlanScriptKeys.ROUTE_AIRSHIP, r.airshipTemplateId() == null ? NONE : q(r.airshipTemplateId()));
        parts.put(PlanScriptKeys.ROUTE_FROM, q(r.fromDock()));
        parts.put(PlanScriptKeys.ROUTE_ID, q(r.id()));
        parts.put(PlanScriptKeys.ROUTE_TO, q(r.toDock()));
        parts.put(PlanScriptKeys.ROUTE_WAYPOINTS,
                bracketed(r.waypoints().stream().map(p -> ints(p.u(), p.v(), p.w())).toList()));
        return dict(parts);
    }

    private static String flow(LogisticsPlan.CargoFlow f) {
        Map<String, String> parts = new TreeMap<>();
        parts.put(PlanScriptKeys.FLOW_FROM, q(f.fromDock()));
        parts.put(PlanScriptKeys.FLOW_ITEM, q(f.itemId()));
        parts.put(PlanScriptKeys.FLOW_PER_MIN, number(f.perMin()));
        parts.put(PlanScriptKeys.FLOW_TO, q(f.toDock()));
        return dict(parts);
    }

    /** {@code node.port}: what the recorder splits at the first separator. */
    private static String portText(PortRef p) {
        return p.nodeId() + PlanApi.NODE_PORT_SEPARATOR + p.port();
    }

    private static String box(Box b) {
        return ints(b.minA(), b.minB(), b.minC(), b.maxA(), b.maxB(), b.maxC());
    }

    // ------------------------------------------------------------------ literal forms

    private static String bool(boolean value) {
        return value ? TRUE : FALSE;
    }

    /** {@code command(arg, arg, ...)}. */
    private static String call(String command, String... args) {
        return call(command, Arrays.asList(args));
    }

    private static String call(String command, List<String> args) {
        return command + "(" + join(args) + ")";
    }

    private static String join(Iterable<String> items) {
        return String.join(ITEM_SEPARATOR, items);
    }

    /** {@code [a, b, c]} from items that are already literals - empty is {@code []}. */
    private static String bracketed(Iterable<String> items) {
        return "[" + join(items) + "]";
    }

    private static String ints(int... values) {
        List<String> items = new ArrayList<>();
        for (int v : values) {
            items.add(String.valueOf(v));
        }
        return bracketed(items);
    }

    /** A dict from pre-rendered value text, keys in the order of the map (callers pass a sorted one). */
    private static String dict(Map<String, String> parts) {
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, String> e : parts.entrySet()) {
            entries.add(q(e.getKey()) + KEY_VALUE_SEPARATOR + e.getValue());
        }
        return "{" + join(entries) + "}";
    }

    /** A list of quoted strings: {@code ["a", "b"]} - empty is {@code []}. */
    private static String list(Iterable<String> items) {
        List<String> quoted = new ArrayList<>();
        for (String s : items) {
            quoted.add(q(s));
        }
        return bracketed(quoted);
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
