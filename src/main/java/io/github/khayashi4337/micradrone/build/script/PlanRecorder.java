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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Records what a construction script says as a {@link PlanPatch}: one command, one operation. Style and mood calls
 * are gathered into a single SetStyle placed where the first of them was called. Malformed values are
 * IllegalArgumentExceptions; the interpreter turns them into script errors with the line.
 *
 * <p>The arguments are the script's live values, so every list/dict is read eagerly into the plan's own immutable
 * types at call time - a script that keeps mutating the value it passed can never change the recorded patch. Values
 * that are not data (functions, None inside params/logistics, sets) are IllegalArgumentExceptions too; the message
 * names the offending argument.
 */
public final class PlanRecorder implements PlanApi {
    private static final int SITE_BOUNDS_SIZE = 6;
    private static final int LOCAL_POS_SIZE = 3;

    private final List<PlanOp> ops = new ArrayList<>();
    private final TreeMap<String, String> palette = new TreeMap<>();
    private final TreeSet<String> mood = new TreeSet<>();
    private int styleAt = -1;
    private final List<String> printed = new ArrayList<>();

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
        ops.add(new PlanOp.SetSite(new Site(dimension, new BuildFrame(new IntPos(x, y, z), Facing.parse(facing)), box, terrainDigest, claimId)));
    }

    @Override
    public void style(String role, String material) {
        touchStyle();
        palette.put(role, material);
    }

    @Override
    public void mood(String tag) {
        touchStyle();
        mood.add(tag);
    }

    @Override
    public void part(String id, String type, String parent, PlanAnchorArgs anchor, Map<String, Object> params, List<String> tags, String label) {
        ops.add(new PlanOp.AddNode(new PlanNode(id, type, parent, toAnchor(anchor), toParams(params), Set.copyOf(tags), label)));
    }

    @Override
    public void updateParams(String id, Map<String, Object> params) {
        ops.add(new PlanOp.UpdateParams(id, toParams(params)));
    }

    @Override
    public void relocate(String id, PlanAnchorArgs anchor) {
        ops.add(new PlanOp.MoveNode(id, toAnchor(anchor)));
    }

    @Override
    public void removePart(String id) {
        ops.add(new PlanOp.RemoveNode(id));
    }

    @Override
    public void connect(String id, String from, String to, String kind, List<String> via, Map<String, Object> constraints) {
        ops.add(new PlanOp.AddConnection(new Connection(id, port(from), port(to), ConnKind.parse(kind),
                via == null ? Routing.AUTO : new Routing.Explicit(via), toConstraints(constraints))));
    }

    @Override
    public void disconnect(String id) {
        ops.add(new PlanOp.RemoveConnection(id));
    }

    @Override
    public void logistics(List<Object> docks, List<Object> routes, List<Object> flows) {
        List<LogisticsPlan.Dock> dockList = new ArrayList<>();
        for (Object o : docks) {
            Map<String, Object> d = dict(o, "ドック");
            List<PortRef> ports = new ArrayList<>();
            for (Object p : list(d.getOrDefault("ports", List.of()), "ports")) {
                ports.add(port(text(p, "ports")));
            }
            dockList.add(new LogisticsPlan.Dock(text(d.get("id"), "id"), box(d.get("pad"), "pad"), box(d.get("clearance"), "clearance"),
                    Facing.parse(text(d.get("approach"), "approach")), ports, strings(d.getOrDefault("connectors", List.of()), "connectors")));
        }
        List<LogisticsPlan.Route> routeList = new ArrayList<>();
        for (Object o : routes) {
            Map<String, Object> r = dict(o, "航路");
            List<LocalPos> pts = new ArrayList<>();
            for (Object p : list(r.getOrDefault("waypoints", List.of()), "waypoints")) {
                List<Object> c = list(p, "waypoints");
                if (c.size() != LOCAL_POS_SIZE) {
                    throw new IllegalArgumentException("経由点は [u, v, w] です");
                }
                pts.add(new LocalPos(integer(c.get(0)), integer(c.get(1)), integer(c.get(2))));
            }
            routeList.add(new LogisticsPlan.Route(text(r.get("id"), "id"), text(r.get("from"), "from"), text(r.get("to"), "to"), pts,
                    r.get("airship") == null || r.get("airship") instanceof MicraNone ? null : text(r.get("airship"), "airship")));
        }
        List<LogisticsPlan.CargoFlow> flowList = new ArrayList<>();
        for (Object o : flows) {
            Map<String, Object> f = dict(o, "流れ");
            if (!(f.get("per_min") instanceof Double perMin) || !Double.isFinite(perMin)) {
                throw new IllegalArgumentException("流れの per_min は数が必要です");
            }
            flowList.add(new LogisticsPlan.CargoFlow(text(f.get("item"), "item"), perMin, text(f.get("from"), "from"), text(f.get("to"), "to")));
        }
        ops.add(new PlanOp.SetLogistics(new LogisticsPlan(dockList, routeList, flowList)));
    }

    @Override
    public void print(String text) {
        printed.add(text);
    }

    // ------------------------------------------------------------------ conversions

    private static Anchor toAnchor(PlanAnchorArgs a) {
        return switch (a.kind()) {
            case ABSOLUTE -> new Anchor.Absolute(new LocalPos(a.u(), a.v(), a.w()), new Rot(a.turns(), a.mirror()));
            case SURFACE -> {
                Side side = Side.parse(a.side());
                if (side != Side.OUTER && side != Side.INNER) {
                    throw new IllegalArgumentException("面の側は \"outer\" か \"inner\" です(" + a.side() + ")");
                }
                yield new Anchor.OnSurface(a.target(), side, a.u(), a.v());
            }
            case SLOT -> new Anchor.InSlot(a.slot(), new Rot(a.turns(), a.mirror()));
        };
    }

    /**
     * Copies the params dict into the plan's own immutable values at call time. {@link ParamValue#fromTree}
     * already refuses non-data (dicts, functions, None, sets); the wrapper names which argument held it.
     */
    private static Map<String, ParamValue> toParams(Map<String, Object> params) {
        Map<String, ParamValue> out = new TreeMap<>();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            try {
                out.put(e.getKey(), ParamValue.fromTree(e.getValue()));
            } catch (IllegalArgumentException bad) {
                throw new IllegalArgumentException("params の「" + e.getKey() + "」はデータの値にしてください: " + bad.getMessage());
            }
        }
        return out;
    }

    private static PortRef port(String text) {
        int dot = text.indexOf('.');
        if (dot <= 0 || dot == text.length() - 1) {
            throw new IllegalArgumentException("\"ノードID.ポート名\" の形にしてください: " + text);
        }
        return new PortRef(text.substring(0, dot), text.substring(dot + 1));
    }

    private static Constraints toConstraints(Map<String, Object> c) {
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
                case "avoid" -> avoid.addAll(strings(e.getValue(), "avoid"));
                case "entry_dirs" -> {
                    for (String d : strings(e.getValue(), "entry_dirs")) {
                        dirs.add(Dir6.parse(d));
                    }
                }
                default -> throw new IllegalArgumentException("constraints に「" + e.getKey()
                        + "」はありません(max_length・avoid・max_turns・entry_dirs)");
            }
        }
        return new Constraints(maxLength, avoid, maxTurns, dirs);
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
        throw new IllegalArgumentException("整数が必要です(" + o + ")");
    }

    private static Box box(Object o, String what) {
        List<Object> l = list(o, what);
        if (l.size() != SITE_BOUNDS_SIZE) {
            throw new IllegalArgumentException(what + "は整数6個です");
        }
        return new Box(integer(l.get(0)), integer(l.get(1)), integer(l.get(2)), integer(l.get(3)), integer(l.get(4)), integer(l.get(5)));
    }
}
