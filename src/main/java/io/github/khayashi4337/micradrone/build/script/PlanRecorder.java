package io.github.khayashi4337.micradrone.build.script;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.ConnKind;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Constraints;
import io.github.khayashi4337.micradrone.build.model.Dir6;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.LogisticsPlan;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.PlanOp;
import io.github.khayashi4337.micradrone.build.model.PlanPatch;
import io.github.khayashi4337.micradrone.build.model.PortRef;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.Routing;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.model.Site;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import io.github.khayashi4337.micradrone.lang.MicraNone;
import io.github.khayashi4337.micradrone.lang.PlanAnchorArgs;
import io.github.khayashi4337.micradrone.lang.PlanApi;
import io.github.khayashi4337.micradrone.lang.PlanBudgetException;
import io.github.khayashi4337.micradrone.lang.PlanValueText;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Records what a construction script says as a {@link PlanPatch}: one command, one operation. Style and mood calls
 * are gathered into a single SetStyle placed where the first of them was called. Malformed values are
 * IllegalArgumentExceptions; the interpreter turns them into script errors with the line.
 *
 * <p>The arguments are the script's live values, so every list/dict is read eagerly into the plan's own immutable
 * types at call time - a script that keeps mutating the value it passed can never change the recorded patch. Values
 * that are not data (functions, None inside params/logistics, sets) are IllegalArgumentExceptions too; the message
 * names the offending argument.
 *
 * <p>Three cumulative budgets bound what ONE recorder retains across every script of a run - the interpreter's
 * allocation counter is per script and cannot see what earlier scripts already recorded: {@link
 * #MAX_RECORDED_ELEMENTS} counts recorded elements (see {@link #chargeRecordedElements}), {@link
 * #MAX_RECORDED_CHARS} counts the characters of every string an operation keeps (see {@link #chargeRecordedChars})
 * and {@link #MAX_PRINTED_CHARS} counts the characters kept in {@link #printed}. Every charge is taken before the
 * elements it covers are built, so a refused operation is never partially recorded. Budget refusals are
 * {@link PlanBudgetException}s so the dispatcher can tell a limit from a malformed value.
 */
public final class PlanRecorder implements PlanApi {
    private static final int SITE_BOUNDS_SIZE = 6;
    private static final int LOCAL_POS_SIZE = 3;
    /**
     * Deepest list nesting one params value may hold (a list of numbers is 1 level, a list of
     * lists of numbers is 2). Must be bounded because the script's values are LIVE objects: a list
     * that contains itself would recurse forever inside {@link ParamValue#fromTree}.
     */
    private static final int MAX_PARAM_DEPTH = 8;
    /**
     * Total nodes (every list and every scalar counts one) across ALL params of one call. A nest
     * of shared references expands exponentially when read as a tree, so the walk stops here
     * instead of materialising it.
     */
    private static final int MAX_PARAM_NODES = 10_000;
    /**
     * Total elements the recorder retains across ALL ops of the run. One recorded operation costs
     * 1 (the op itself) + the nodes {@link #boundedCopy} walks for its params + {@code tags.size()}
     * + {@code via.size()} + the {@code avoid}/{@code entry_dirs} list sizes of its constraints;
     * for {@link #logistics} it is 1 + 1 per dock/route/flow + each dock's {@code ports} and
     * {@code connectors} sizes + each route's {@code waypoints} size; a {@code style} or
     * {@code mood} call costs 1. The per-call {@link #MAX_PARAM_DEPTH}/{@link #MAX_PARAM_NODES}
     * limits keep ONE call honest; this cumulative one keeps the whole run from filling the heap
     * with recorded copies.
     */
    private static final long MAX_RECORDED_ELEMENTS = 200_000;
    /**
     * Total characters of the strings the recorder retains across ALL ops of the run: an element
     * counts 1 whatever its text weighs, so the strings get their own budget. Every retained
     * string costs its {@code length()} (a null string costs 0): ids, names, types, labels, tags,
     * the anchor's surface target id and slot id, port texts, the {@code via}/{@code
     * avoid}/{@code entry_dirs} entries, the dock/route/flow fields of {@link #logistics}, and
     * every params key and string scalar {@link #boundedCopy} walks. Enum-parsed texts (site
     * facing, anchor side, connect kind, dock approach, entry_dirs names) are charged on the
     * string that was read, before the parse that may reject it.
     */
    private static final long MAX_RECORDED_CHARS = 1_000_000;
    /** Total characters {@link #printed} may retain across the whole run - same reason as {@link #MAX_RECORDED_ELEMENTS}. */
    private static final long MAX_PRINTED_CHARS = 1_000_000;
    private static final List<String> DOCK_KEYS = List.of("id", "pad", "clearance", "approach", "ports", "connectors");
    private static final List<String> ROUTE_KEYS = List.of("id", "from", "to", "waypoints", "airship");
    private static final List<String> FLOW_KEYS = List.of("item", "per_min", "from", "to");
    /** The only {@link Side} values a surface anchor accepts - the field's real allowed set. */
    private static final Side[] SURFACE_SIDES = {Side.OUTER, Side.INNER};

    private final List<PlanOp> ops = new ArrayList<>();
    private final TreeMap<String, String> palette = new TreeMap<>();
    private final TreeSet<String> mood = new TreeSet<>();
    private int styleAt = -1;
    private final List<String> printed = new ArrayList<>();
    private long recordedElements;
    private long recordedChars;
    private long printedChars;

    public PlanPatch toPatch(String patchId, int baseRevision, String stageId) {
        List<PlanOp> out = new ArrayList<>(ops);
        if (styleAt >= 0) {
            out.set(styleAt, new PlanOp.SetStyle(new StyleSpec(palette, mood)));
        }
        return new PlanPatch(patchId, baseRevision, stageId, out);
    }

    public List<String> printed() {
        return List.copyOf(printed);
    }

    private void touchStyle() {
        if (styleAt < 0) {
            styleAt = ops.size();
            ops.add(null); // replaced by the gathered SetStyle in toPatch
        }
    }

    @Override
    public void site(String dimension, int x, int y, int z, String facing, int[] bounds, String terrainDigest, String claimId) {
        if (bounds.length != SITE_BOUNDS_SIZE) {
            throw new IllegalArgumentException("範囲は整数6個 [minU,minV,minW,maxU,maxV,maxW] です");
        }
        Box box = new Box(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
        chargeRecordedElements(1);
        chargeRecordedChars(textChars(dimension) + textChars(facing) + textChars(terrainDigest) + textChars(claimId));
        ops.add(new PlanOp.SetSite(new Site(dimension,
                new BuildFrame(new IntPos(x, y, z), parseEnum("向き", facing, Facing.values(), Facing::parse)),
                box, terrainDigest, claimId)));
    }

    @Override
    public void style(String role, String material) {
        chargeRecordedElements(1);
        chargeRecordedChars(textChars(role) + textChars(material));
        touchStyle();
        palette.put(role, material);
    }

    @Override
    public void mood(String tag) {
        chargeRecordedElements(1);
        chargeRecordedChars(textChars(tag));
        touchStyle();
        mood.add(tag);
    }

    @Override
    public void part(String id, String type, String parent, PlanAnchorArgs anchor, Map<String, Object> params, List<String> tags, String label) {
        chargeRecordedElements(1 + tags.size());
        chargeRecordedChars(textChars(id) + textChars(type) + textChars(parent) + textChars(label));
        chargeRecordedCharsOf(tags);
        ops.add(new PlanOp.AddNode(new PlanNode(id, type, parent, toAnchor(anchor), toParams(params), Set.copyOf(tags), label)));
    }

    @Override
    public void updateParams(String id, Map<String, Object> params) {
        chargeRecordedElements(1);
        chargeRecordedChars(textChars(id));
        ops.add(new PlanOp.UpdateParams(id, toParams(params)));
    }

    @Override
    public void relocate(String id, PlanAnchorArgs anchor) {
        chargeRecordedElements(1);
        chargeRecordedChars(textChars(id));
        ops.add(new PlanOp.MoveNode(id, toAnchor(anchor)));
    }

    @Override
    public void removePart(String id) {
        chargeRecordedElements(1);
        chargeRecordedChars(textChars(id));
        ops.add(new PlanOp.RemoveNode(id));
    }

    @Override
    public void connect(String id, String from, String to, String kind, List<String> via, Map<String, Object> constraints) {
        chargeRecordedElements(1 + (via == null ? 0 : via.size()));
        chargeRecordedChars(textChars(id) + textChars(from) + textChars(to) + textChars(kind));
        if (via != null) {
            chargeRecordedCharsOf(via);
        }
        ops.add(new PlanOp.AddConnection(new Connection(id, port(from), port(to),
                parseEnum("kind", kind, ConnKind.values(), ConnKind::parse),
                via == null ? Routing.AUTO : new Routing.Explicit(via), toConstraints(constraints))));
    }

    @Override
    public void disconnect(String id) {
        chargeRecordedElements(1);
        chargeRecordedChars(textChars(id));
        ops.add(new PlanOp.RemoveConnection(id));
    }

    @Override
    public void logistics(List<Object> docks, List<Object> routes, List<Object> flows) {
        chargeRecordedElements(1); // the SetLogistics op itself
        List<LogisticsPlan.Dock> dockList = new ArrayList<>();
        for (Object o : docks) {
            Map<String, Object> d = dict(o, "ドック");
            checkKeys(d, "ドック", DOCK_KEYS);
            List<Object> portSpecs = list(d.getOrDefault("ports", List.of()), "ports");
            List<Object> connectorSpecs = list(d.getOrDefault("connectors", List.of()), "connectors");
            // charged BEFORE the PortRef list is built: a dock whose ports list is huge must be
            // refused here, not after a PortRef per entry has been materialised
            chargeRecordedElements(1 + portSpecs.size() + connectorSpecs.size());
            String dockId = text(d.get("id"), "id");
            String approach = text(d.get("approach"), "approach");
            chargeRecordedChars(textChars(dockId) + textChars(approach));
            List<PortRef> ports = new ArrayList<>();
            for (Object p : portSpecs) {
                String portSpec = text(p, "ports");
                chargeRecordedChars(portSpec.length());
                ports.add(port(portSpec));
            }
            List<String> connectorIds = strings(connectorSpecs, "connectors");
            chargeRecordedCharsOf(connectorIds);
            dockList.add(new LogisticsPlan.Dock(dockId, box(d.get("pad"), "pad"), box(d.get("clearance"), "clearance"),
                    parseEnum("approach", approach, Facing.values(), Facing::parse), ports, connectorIds));
        }
        List<LogisticsPlan.Route> routeList = new ArrayList<>();
        for (Object o : routes) {
            Map<String, Object> r = dict(o, "航路");
            checkKeys(r, "航路", ROUTE_KEYS);
            List<Object> waypointSpecs = list(r.getOrDefault("waypoints", List.of()), "waypoints");
            chargeRecordedElements(1 + waypointSpecs.size());
            List<LocalPos> pts = new ArrayList<>();
            for (Object p : waypointSpecs) {
                List<Object> c = list(p, "waypoints");
                if (c.size() != LOCAL_POS_SIZE) {
                    throw new IllegalArgumentException("経由点は [u, v, w] です");
                }
                pts.add(new LocalPos(integer(c.get(0)), integer(c.get(1)), integer(c.get(2))));
            }
            String routeId = text(r.get("id"), "id");
            String from = text(r.get("from"), "from");
            String to = text(r.get("to"), "to");
            String airship = r.get("airship") == null || r.get("airship") instanceof MicraNone ? null : text(r.get("airship"), "airship");
            chargeRecordedChars(textChars(routeId) + textChars(from) + textChars(to) + textChars(airship));
            routeList.add(new LogisticsPlan.Route(routeId, from, to, pts, airship));
        }
        List<LogisticsPlan.CargoFlow> flowList = new ArrayList<>();
        for (Object o : flows) {
            Map<String, Object> f = dict(o, "流れ");
            checkKeys(f, "流れ", FLOW_KEYS);
            if (!(f.get("per_min") instanceof Double perMin) || !Double.isFinite(perMin)) {
                throw new IllegalArgumentException("流れの per_min は数が必要です");
            }
            chargeRecordedElements(1);
            String item = text(f.get("item"), "item");
            String from = text(f.get("from"), "from");
            String to = text(f.get("to"), "to");
            chargeRecordedChars(textChars(item) + textChars(from) + textChars(to));
            flowList.add(new LogisticsPlan.CargoFlow(item, perMin, from, to));
        }
        ops.add(new PlanOp.SetLogistics(new LogisticsPlan(dockList, routeList, flowList)));
    }

    @Override
    public void print(String text) {
        // checked BEFORE the counter moves: a refused text is retained nowhere, so its
        // characters must not be counted - a following small print still fits when there is room
        if (printedChars + text.length() > MAX_PRINTED_CHARS) {
            throw new PlanBudgetException("出力が大きすぎます(記録する文字は合計 " + MAX_PRINTED_CHARS + " 字まで)");
        }
        printedChars += text.length();
        printed.add(text);
    }

    // ------------------------------------------------------------------ conversions

    /**
     * Counts elements the run is about to retain against {@link #MAX_RECORDED_ELEMENTS}; a refusal
     * is a {@link PlanBudgetException}, which the dispatcher reports as a limit (E-SCRIPT-LIMIT).
     */
    private void chargeRecordedElements(long units) {
        recordedElements += units;
        if (recordedElements > MAX_RECORDED_ELEMENTS) {
            throw new PlanBudgetException("記録する計画が大きすぎます(要素は合計 " + MAX_RECORDED_ELEMENTS + " 個まで)");
        }
    }

    /**
     * Counts characters of strings the run is about to retain against {@link #MAX_RECORDED_CHARS}
     * - the element budget counts nodes, not what the text inside them weighs. A refusal is a
     * {@link PlanBudgetException} for the same reason.
     */
    private void chargeRecordedChars(long chars) {
        recordedChars += chars;
        if (recordedChars > MAX_RECORDED_CHARS) {
            throw new PlanBudgetException("記録する計画が大きすぎます(記録する文字は合計 " + MAX_RECORDED_CHARS + " 字まで)");
        }
    }

    /** Charges the length of every string in the list; a null entry costs 0. */
    private void chargeRecordedCharsOf(Iterable<String> strings) {
        long chars = 0;
        for (String s : strings) {
            chars += textChars(s);
        }
        chargeRecordedChars(chars);
    }

    /** The characters one retained string costs; null (nothing kept) costs 0. */
    private static long textChars(String s) {
        return s == null ? 0 : s.length();
    }

    /**
     * Builds the recorded anchor, charging its texts BEFORE the {@link Anchor} exists: the
     * surface target id and the slot id are retained verbatim (OnSurface's nodeId, InSlot's
     * slotId), and the side text is charged on the string that was read like the other
     * enum-parsed texts. Fields the kind does not use are null and cost 0.
     */
    private Anchor toAnchor(PlanAnchorArgs a) {
        chargeRecordedChars(textChars(a.target()) + textChars(a.side()) + textChars(a.slot()));
        return switch (a.kind()) {
            case ABSOLUTE -> new Anchor.Absolute(new LocalPos(a.u(), a.v(), a.w()), new Rot(a.turns(), a.mirror()));
            case SURFACE -> {
                Side side = parseEnum("面の側", a.side(), SURFACE_SIDES, Side::parse);
                if (side != Side.OUTER && side != Side.INNER) {
                    throw new IllegalArgumentException("面の側は \"outer\" か \"inner\" です(" + PlanValueText.describe(a.side()) + ")");
                }
                yield new Anchor.OnSurface(a.target(), side, a.u(), a.v());
            }
            case SLOT -> new Anchor.InSlot(a.slot(), new Rot(a.turns(), a.mirror()));
        };
    }

    /**
     * Copies the params dict into the plan's own immutable values at call time. {@link ParamValue#fromTree}
     * already refuses non-data (dicts, functions, None, sets); the wrapper names which argument held it.
     * The value is first rebuilt inside fresh lists under {@link #MAX_PARAM_DEPTH}/
     * {@link #MAX_PARAM_NODES}, so a cyclic list cannot make {@code fromTree} recurse forever and a
     * shared-reference nest cannot expand into an unbounded copy inside one call.
     */
    private Map<String, ParamValue> toParams(Map<String, Object> params) {
        Map<String, ParamValue> out = new TreeMap<>();
        ParamBudget budget = new ParamBudget();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            chargeRecordedChars(textChars(e.getKey()));
            Object value = boundedCopy(e.getKey(), e.getValue(), budget, 0);
            try {
                out.put(e.getKey(), ParamValue.fromTree(value));
            } catch (IllegalArgumentException bad) {
                throw new IllegalArgumentException("params の「" + PlanValueText.describe(e.getKey())
                        + "」はデータの値にしてください: " + bad.getMessage());
            }
        }
        return out;
    }

    /** The node count one {@link #toParams} call is still allowed to walk, shared across its keys. */
    private static final class ParamBudget {
        int nodes;
    }

    /**
     * Rebuilds {@code value} as fresh lists within the depth/node limits shared by the whole call.
     * Scalars are carried by value; anything that is not a list passes through UNCHANGED so
     * {@link ParamValue#fromTree} keeps producing its existing rejection for non-data - the copy
     * deliberately does not look inside it. Every walked node also counts against {@link
     * #MAX_RECORDED_ELEMENTS}, so the copies a run keeps recording cannot outgrow the heap even
     * though each single call stays under {@link #MAX_PARAM_NODES}.
     */
    private Object boundedCopy(String key, Object value, ParamBudget budget, int depth) {
        budget.nodes++;
        if (budget.nodes > MAX_PARAM_NODES) {
            throw tooDeep(key);
        }
        chargeRecordedElements(1);
        if (!(value instanceof List<?> list)) {
            // a scalar the copy keeps - a string's characters count against the run-wide
            // character budget on top of its one element
            if (value instanceof String s) {
                chargeRecordedChars(s.length());
            }
            return value;
        }
        // a list nested inside `depth` lists is level depth+1; level 9 or deeper is refused, so an
        // empty list at level 9 is caught too and a cyclic list can never recurse without end
        if (depth >= MAX_PARAM_DEPTH) {
            throw tooDeep(key);
        }
        List<Object> copy = new ArrayList<>(list.size());
        for (Object item : list) {
            copy.add(boundedCopy(key, item, budget, depth + 1));
        }
        return copy;
    }

    private static IllegalArgumentException tooDeep(String key) {
        return new IllegalArgumentException("params の「" + PlanValueText.describe(key) + "」は、深さ "
                + MAX_PARAM_DEPTH + " 段・合計 " + MAX_PARAM_NODES + " 要素までです");
    }

    /**
     * Parses one of the script-facing enums ({@link Facing}, {@link ConnKind}, {@link Side},
     * {@link Dir6}) with a refusal that names the FIELD and the allowed lowercase names - never
     * the raw text: {@code Enum.valueOf}'s own "No enum constant" message quotes the script's
     * input verbatim, and that input can be a hundred thousand characters that must never land
     * in an issue.
     */
    private static <E extends Enum<E>> E parseEnum(String field, String text, E[] allowed,
            Function<String, E> parse) {
        try {
            return parse.apply(text);
        } catch (IllegalArgumentException e) {
            List<String> names = new ArrayList<>(allowed.length);
            for (E value : allowed) {
                names.add("\"" + value.name().toLowerCase(Locale.ROOT) + "\"");
            }
            throw new IllegalArgumentException(field + "は " + String.join("・", names) + " のどれかです");
        }
    }

    private static PortRef port(String text) {
        int dot = text.indexOf('.');
        if (dot <= 0 || dot == text.length() - 1) {
            throw new IllegalArgumentException("\"ノードID.ポート名\" の形にしてください: " + PlanValueText.describe(text));
        }
        return new PortRef(text.substring(0, dot), text.substring(dot + 1));
    }

    private Constraints toConstraints(Map<String, Object> c) {
        if (c == null) {
            return Constraints.NONE;
        }
        Integer maxLength = null;
        Integer maxTurns = null;
        Set<String> avoid = new TreeSet<>();
        Set<Dir6> dirs = new TreeSet<>();
        for (Map.Entry<String, Object> e : c.entrySet()) {
            switch (e.getKey()) {
                case "max_length" -> maxLength = integer(e.getValue());
                case "max_turns" -> maxTurns = integer(e.getValue());
                case "avoid" -> {
                    List<String> names = strings(e.getValue(), "avoid");
                    chargeRecordedElements(names.size());
                    chargeRecordedCharsOf(names);
                    avoid.addAll(names);
                }
                case "entry_dirs" -> {
                    List<String> names = strings(e.getValue(), "entry_dirs");
                    chargeRecordedElements(names.size());
                    chargeRecordedCharsOf(names);
                    for (String d : names) {
                        dirs.add(parseEnum("entry_dirs", d, Dir6.values(), Dir6::parse));
                    }
                }
                default -> throw new IllegalArgumentException("constraints に「" + PlanValueText.describe(e.getKey())
                        + "」はありません(max_length・avoid・max_turns・entry_dirs)");
            }
        }
        return new Constraints(maxLength, avoid, maxTurns, dirs);
    }

    /** Every key of a logistics dict must be one of the documented ones, like {@link #toConstraints} already requires. */
    private static void checkKeys(Map<String, Object> dict, String what, List<String> allowedKeys) {
        for (Object key : dict.keySet()) {
            if (!(key instanceof String) || !allowedKeys.contains(key)) {
                throw new IllegalArgumentException(what + "に「" + PlanValueText.describe(key)
                        + "」はありません(" + String.join("・", allowedKeys) + ")");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dict(Object o, String what) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException(what + "は辞書({…})で書いてください");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o, String what) {
        if (o instanceof List<?> l) {
            return (List<Object>) l;
        }
        throw new IllegalArgumentException(what + "はリストで書いてください");
    }

    private static String text(Object o, String what) {
        if (o instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException(what + "は文字列が必要です");
    }

    private static List<String> strings(Object o, String what) {
        List<String> out = new ArrayList<>();
        for (Object item : list(o, what)) {
            out.add(text(item, what));
        }
        return out;
    }

    private static int integer(Object o) {
        if (o instanceof Double d && d == Math.rint(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
            return (int) (double) d;
        }
        throw new IllegalArgumentException("整数が必要です(" + PlanValueText.describe(o) + ")");
    }

    private static Box box(Object o, String what) {
        List<Object> l = list(o, what);
        if (l.size() != SITE_BOUNDS_SIZE) {
            throw new IllegalArgumentException(what + "は整数6個です");
        }
        return new Box(integer(l.get(0)), integer(l.get(1)), integer(l.get(2)), integer(l.get(3)), integer(l.get(4)), integer(l.get(5)));
    }
}
