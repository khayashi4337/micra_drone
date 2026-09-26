package io.github.khayashi4337.micradrone.build.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * JSON tree conversion of plans and patches, and the content hash. Trees are plain Map/List/String/Long/Double/
 * Boolean/null values, so they work with {@code MiniJson} and {@code CanonicalJson}. Reading is strict about
 * structure and reports the path of the bad value.
 */
public final class PlanJson {
    // Keys of the JSON form. The same names are used for writing and reading, and they are hashed.
    private static final String KEY_SCHEMA_VERSION = "schemaVersion";
    private static final String KEY_PLAN_ID = "planId";
    private static final String KEY_REVISION = "revision";
    private static final String KEY_PARENT_REVISION = "parentRevision";
    private static final String KEY_PROVENANCE = "provenance";
    private static final String KEY_SITE = "site";
    private static final String KEY_STYLE = "style";
    private static final String KEY_NODES = "nodes";
    private static final String KEY_CONNECTIONS = "connections";
    private static final String KEY_LOGISTICS = "logistics";

    private static final String KEY_DIMENSION = "dimension";
    private static final String KEY_ORIGIN = "origin";
    private static final String KEY_FACING = "facing";
    private static final String KEY_BOUNDS = "bounds";
    private static final String KEY_TERRAIN_DIGEST = "terrainDigest";
    private static final String KEY_CLAIM_ID = "claimId";

    private static final String KEY_PALETTE = "palette";
    private static final String KEY_MOOD_TAGS = "moodTags";

    private static final String KEY_ID = "id";
    private static final String KEY_TYPE = "type";
    private static final String KEY_PARENT = "parent";
    private static final String KEY_ANCHOR = "anchor";
    private static final String KEY_PARAMS = "params";
    private static final String KEY_TAGS = "tags";
    private static final String KEY_LABEL = "label";

    private static final String KEY_KIND = "kind";
    private static final String KEY_POS = "pos";
    private static final String KEY_ROT = "rot";
    private static final String KEY_NODE = "node";
    private static final String KEY_SIDE = "side";
    private static final String KEY_U = "u";
    private static final String KEY_V = "v";
    private static final String KEY_SLOT = "slot";
    private static final String KEY_TURNS = "turns";
    private static final String KEY_MIRROR = "mirror";

    // Members of a {key, value} parameter pair (the compact array form of "params").
    private static final String KEY_PAIR_KEY = "key";
    private static final String KEY_PAIR_VALUE = "value";
    /** A rot that names no turns means no rotation. */
    private static final int DEFAULT_QUARTER_TURNS = 0;

    private static final String KEY_PORT = "port";
    private static final String KEY_FROM = "from";
    private static final String KEY_TO = "to";
    private static final String KEY_ROUTING = "routing";
    private static final String KEY_MODE = "mode";
    private static final String KEY_VIA = "via";
    private static final String KEY_CONSTRAINTS = "constraints";
    private static final String KEY_MAX_LENGTH = "maxLength";
    private static final String KEY_AVOID = "avoid";
    private static final String KEY_MAX_TURNS = "maxTurns";
    private static final String KEY_ENTRY_DIRS = "entryDirs";

    private static final String KEY_DOCKS = "docks";
    private static final String KEY_ROUTES = "routes";
    private static final String KEY_FLOWS = "flows";
    private static final String KEY_PAD = "pad";
    private static final String KEY_CLEARANCE = "clearance";
    private static final String KEY_APPROACH = "approach";
    private static final String KEY_PORTS = "ports";
    private static final String KEY_CONNECTORS = "connectors";
    private static final String KEY_WAYPOINTS = "waypoints";
    private static final String KEY_AIRSHIP = "airship";
    private static final String KEY_ITEM = "item";
    private static final String KEY_PER_MIN = "perMin";

    private static final String KEY_STAGE_ID = "stageId";
    private static final String KEY_MODEL_ID = "modelId";
    private static final String KEY_PROMPT_HASH = "promptHash";
    private static final String KEY_IMAGE_IDS = "imageIds";
    private static final String KEY_CREATED_AT_MILLIS = "createdAtMillis";

    private static final String KEY_PATCH_ID = "patchId";
    private static final String KEY_BASE_REVISION = "baseRevision";
    private static final String KEY_OPS = "ops";
    private static final String KEY_OP = "op";
    private static final String KEY_CONNECTION = "connection";

    // Values that tell the variants of an anchor, a routing and an operation apart.
    private static final String ANCHOR_ABSOLUTE = "absolute";
    private static final String ANCHOR_SURFACE = "surface";
    private static final String ANCHOR_SLOT = "slot";

    private static final String ROUTING_AUTO = "auto";
    private static final String ROUTING_EXPLICIT = "explicit";

    private static final String OP_ADD_NODE = "add_node";
    private static final String OP_UPDATE_PARAMS = "update_params";
    private static final String OP_MOVE_NODE = "move_node";
    private static final String OP_REMOVE_NODE = "remove_node";
    private static final String OP_ADD_CONNECTION = "add_connection";
    private static final String OP_REMOVE_CONNECTION = "remove_connection";
    private static final String OP_SET_STYLE = "set_style";
    private static final String OP_SET_SITE = "set_site";
    private static final String OP_SET_LOGISTICS = "set_logistics";

    // Fixed-size integer arrays: a position, a site origin and a box.
    private static final int POS_SIZE = 3;
    private static final String POS_SHAPE = "[u, v, w]";
    private static final int ORIGIN_SIZE = 3;
    private static final String ORIGIN_SHAPE = "[x, y, z]";
    private static final int BOX_SIZE = 6;
    private static final String BOX_SHAPE = "six integers [minA, minB, minC, maxA, maxB, maxC]";

    private PlanJson() {
    }

    // ------------------------------------------------------------------ plan -> tree

    public static Map<String, Object> toTree(SemanticPlan plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_SCHEMA_VERSION, plan.schemaVersion());
        m.put(KEY_PLAN_ID, plan.planId());
        m.put(KEY_REVISION, plan.revision());
        m.put(KEY_PARENT_REVISION, plan.parentRevision());
        m.putAll(body(plan, false));
        m.put(KEY_PROVENANCE, provenanceTree(plan.provenance()));
        return m;
    }

    /** The tree the content hash is computed from: no meta fields, nodes and connections in id order. */
    public static Map<String, Object> contentTree(SemanticPlan plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_SCHEMA_VERSION, plan.schemaVersion());
        m.putAll(body(plan, true));
        return m;
    }

    public static String contentHash(SemanticPlan plan) {
        return Hashing.sha256Hex(CanonicalJson.write(contentTree(plan)));
    }

    private static Map<String, Object> body(SemanticPlan plan, boolean canonicalOrder) {
        List<PlanNode> nodes = new ArrayList<>(plan.nodes());
        List<Connection> connections = new ArrayList<>(plan.connections());
        if (canonicalOrder) {
            nodes.sort(Comparator.comparing(PlanNode::id));
            connections.sort(Comparator.comparing(Connection::id));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_SITE, plan.site() == null ? null : siteTree(plan.site()));
        m.put(KEY_STYLE, styleTree(plan.style()));
        m.put(KEY_NODES, trees(nodes, PlanJson::nodeToTree));
        m.put(KEY_CONNECTIONS, trees(connections, PlanJson::connectionToTree));
        m.put(KEY_LOGISTICS, plan.logistics() == null ? null : logisticsTree(plan.logistics()));
        return m;
    }

    /** Converts every element with {@code toTree}, keeping the order of {@code items}. */
    private static <T> List<Object> trees(Collection<T> items, Function<? super T, ?> toTree) {
        List<Object> out = new ArrayList<>(items.size());
        for (T item : items) {
            out.add(toTree.apply(item));
        }
        return out;
    }

    private static List<Object> intTree(int... values) {
        List<Object> out = new ArrayList<>(values.length);
        for (int value : values) {
            out.add((long) value);
        }
        return out;
    }

    public static List<Object> posTree(LocalPos p) {
        return intTree(p.u(), p.v(), p.w());
    }

    public static List<Object> boxTree(Box b) {
        return intTree(b.minA(), b.minB(), b.minC(), b.maxA(), b.maxB(), b.maxC());
    }

    static Map<String, Object> rotTree(Rot r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_TURNS, r.quarterTurns());
        m.put(KEY_MIRROR, r.mirror());
        return m;
    }

    static Map<String, Object> anchorTree(Anchor anchor) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (anchor) {
            case Anchor.Absolute a -> {
                m.put(KEY_KIND, ANCHOR_ABSOLUTE);
                m.put(KEY_POS, posTree(a.pos()));
                m.put(KEY_ROT, rotTree(a.rot()));
            }
            case Anchor.OnSurface s -> {
                m.put(KEY_KIND, ANCHOR_SURFACE);
                m.put(KEY_NODE, s.nodeId());
                m.put(KEY_SIDE, s.side().lower());
                m.put(KEY_U, s.u());
                m.put(KEY_V, s.v());
            }
            case Anchor.InSlot s -> {
                m.put(KEY_KIND, ANCHOR_SLOT);
                m.put(KEY_SLOT, s.slotId());
                m.put(KEY_ROT, rotTree(s.rot()));
            }
        }
        return m;
    }

    static Map<String, Object> siteTree(Site s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_DIMENSION, s.dimension());
        IntPos origin = s.frame().origin();
        m.put(KEY_ORIGIN, intTree(origin.x(), origin.y(), origin.z()));
        m.put(KEY_FACING, s.frame().facing().lower());
        m.put(KEY_BOUNDS, boxTree(s.localBounds()));
        m.put(KEY_TERRAIN_DIGEST, s.terrainDigest());
        m.put(KEY_CLAIM_ID, s.claimId());
        return m;
    }

    static Map<String, Object> styleTree(StyleSpec s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_PALETTE, new TreeMap<>(s.palette()));
        m.put(KEY_MOOD_TAGS, new ArrayList<>(s.moodTags()));
        return m;
    }

    public static Map<String, Object> nodeToTree(PlanNode n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_ID, n.id());
        m.put(KEY_TYPE, n.type());
        m.put(KEY_PARENT, n.parent());
        m.put(KEY_ANCHOR, anchorTree(n.anchor()));
        m.put(KEY_PARAMS, paramsTree(n.params()));
        m.put(KEY_TAGS, new ArrayList<>(n.tags()));
        m.put(KEY_LABEL, n.label());
        return m;
    }

    static Map<String, Object> paramsTree(Map<String, ParamValue> params) {
        Map<String, Object> m = new TreeMap<>();
        for (Map.Entry<String, ParamValue> e : params.entrySet()) {
            m.put(e.getKey(), e.getValue().toTree());
        }
        return m;
    }

    static Map<String, Object> portTree(PortRef p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_NODE, p.nodeId());
        m.put(KEY_PORT, p.port());
        return m;
    }

    public static Map<String, Object> connectionToTree(Connection c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_ID, c.id());
        m.put(KEY_FROM, portTree(c.from()));
        m.put(KEY_TO, portTree(c.to()));
        m.put(KEY_KIND, c.kind().lower());
        m.put(KEY_ROUTING, routingTree(c.routing()));
        m.put(KEY_CONSTRAINTS, constraintsTree(c.constraints()));
        return m;
    }

    static Map<String, Object> routingTree(Routing r) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (r instanceof Routing.Explicit e) {
            m.put(KEY_MODE, ROUTING_EXPLICIT);
            m.put(KEY_VIA, new ArrayList<>(e.viaNodeIds()));
        } else {
            m.put(KEY_MODE, ROUTING_AUTO);
        }
        return m;
    }

    static Map<String, Object> constraintsTree(Constraints c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_MAX_LENGTH, c.maxLength());
        m.put(KEY_AVOID, new ArrayList<>(c.avoidNodeIds()));
        m.put(KEY_MAX_TURNS, c.maxTurns());
        m.put(KEY_ENTRY_DIRS, trees(c.allowedEntryDirs(), Dir6::lower));
        return m;
    }

    static Map<String, Object> logisticsTree(LogisticsPlan l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_DOCKS, trees(l.docks(), PlanJson::dockTree));
        m.put(KEY_ROUTES, trees(l.routes(), PlanJson::routeTree));
        m.put(KEY_FLOWS, trees(l.flows(), PlanJson::flowTree));
        return m;
    }

    private static Map<String, Object> dockTree(LogisticsPlan.Dock d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_ID, d.id());
        m.put(KEY_PAD, boxTree(d.padBox()));
        m.put(KEY_CLEARANCE, boxTree(d.clearanceBox()));
        m.put(KEY_APPROACH, d.approach().lower());
        m.put(KEY_PORTS, trees(d.linkedPorts(), PlanJson::portTree));
        m.put(KEY_CONNECTORS, new ArrayList<>(d.dockingConnectorNodeIds()));
        return m;
    }

    private static Map<String, Object> routeTree(LogisticsPlan.Route r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_ID, r.id());
        m.put(KEY_FROM, r.fromDock());
        m.put(KEY_TO, r.toDock());
        m.put(KEY_WAYPOINTS, trees(r.waypoints(), PlanJson::posTree));
        m.put(KEY_AIRSHIP, r.airshipTemplateId());
        return m;
    }

    private static Map<String, Object> flowTree(LogisticsPlan.CargoFlow f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_ITEM, f.itemId());
        m.put(KEY_PER_MIN, f.perMin());
        m.put(KEY_FROM, f.fromDock());
        m.put(KEY_TO, f.toDock());
        return m;
    }

    static Map<String, Object> provenanceTree(Provenance p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_STAGE_ID, p.stageId());
        m.put(KEY_MODEL_ID, p.modelId());
        m.put(KEY_PROMPT_HASH, p.promptHash());
        m.put(KEY_IMAGE_IDS, new ArrayList<>(p.imageIds()));
        m.put(KEY_CREATED_AT_MILLIS, p.createdAtMillis());
        return m;
    }

    // ------------------------------------------------------------------ tree -> plan

    public static SemanticPlan planFromTree(Object tree) {
        String path = JsonTree.ROOT_PATH;
        Map<String, Object> m = JsonTree.obj(tree, path);
        int version = JsonTree.reqInt(m, KEY_SCHEMA_VERSION, path);
        if (version != SemanticPlan.SCHEMA_VERSION) {
            throw JsonTree.bad(JsonTree.child(path, KEY_SCHEMA_VERSION), "unsupported schemaVersion " + version
                    + " (this build reads " + SemanticPlan.SCHEMA_VERSION + "); the data is refused, not converted");
        }
        String planId = JsonTree.reqStr(m, KEY_PLAN_ID, path);
        int revision = JsonTree.reqInt(m, KEY_REVISION, path);
        Integer parentRevision = JsonTree.optInt(m, KEY_PARENT_REVISION, path);
        Site site = JsonTree.opt(m, KEY_SITE, path, PlanJson::siteFromTree);
        StyleSpec style = Objects.requireNonNullElse(JsonTree.opt(m, KEY_STYLE, path, PlanJson::styleFromTree),
                StyleSpec.EMPTY);
        List<PlanNode> nodes = JsonTree.reqList(m, KEY_NODES, path, PlanJson::nodeFromTree);
        List<Connection> connections = JsonTree.reqList(m, KEY_CONNECTIONS, path, PlanJson::connectionFromTree);
        LogisticsPlan logistics = JsonTree.opt(m, KEY_LOGISTICS, path, PlanJson::logisticsFromTree);
        Provenance provenance = Objects.requireNonNullElse(
                JsonTree.opt(m, KEY_PROVENANCE, path, PlanJson::provenanceFromTree), Provenance.NONE);
        return new SemanticPlan(version, planId, revision, parentRevision, site, style, nodes, connections, logistics,
                provenance);
    }

    /** Reads an array of exactly {@code size} integers; {@code shape} names the expected form for the error. */
    private static int[] intsFromTree(Object v, String path, int size, String shape) {
        List<Object> a = JsonTree.arr(v, path);
        if (a.size() != size) {
            throw JsonTree.bad(path, "expected " + shape);
        }
        int[] n = new int[size];
        for (int i = 0; i < size; i++) {
            n[i] = JsonTree.integer(a.get(i), JsonTree.item(path, i));
        }
        return n;
    }

    static LocalPos posFromTree(Object v, String path) {
        int[] n = intsFromTree(v, path, POS_SIZE, POS_SHAPE);
        return new LocalPos(n[0], n[1], n[2]);
    }

    static Box boxFromTree(Object v, String path) {
        int[] n = intsFromTree(v, path, BOX_SIZE, BOX_SHAPE);
        try {
            return new Box(n[0], n[1], n[2], n[3], n[4], n[5]);
        } catch (IllegalArgumentException e) {
            throw JsonTree.bad(path, e.getMessage());
        }
    }

    static Rot rotFromTree(Object v, String path) {
        if (v == null) {
            return Rot.NONE;
        }
        Map<String, Object> m = JsonTree.obj(v, path);
        Integer turns = JsonTree.optInt(m, KEY_TURNS, path);
        Object mirrorValue = m.get(KEY_MIRROR);
        boolean mirror = mirrorValue != null && JsonTree.bool(mirrorValue, JsonTree.child(path, KEY_MIRROR));
        return new Rot(turns == null ? DEFAULT_QUARTER_TURNS : turns, mirror);
    }

    static <E extends Enum<E>> E enumOf(Class<E> type, Function<String, E> parse, Object v, String path) {
        String text = JsonTree.str(v, path);
        try {
            return parse.apply(text);
        } catch (IllegalArgumentException e) {
            throw JsonTree.bad(path, "unknown " + type.getSimpleName() + " \"" + text + "\"");
        }
    }

    static Anchor anchorFromTree(Object v, String path) {
        Map<String, Object> m = JsonTree.obj(v, path);
        String kind = JsonTree.reqStr(m, KEY_KIND, path);
        return switch (kind) {
            case ANCHOR_ABSOLUTE -> new Anchor.Absolute(posFromTree(JsonTree.req(m, KEY_POS, path),
                    JsonTree.child(path, KEY_POS)), rotFromTree(m.get(KEY_ROT), JsonTree.child(path, KEY_ROT)));
            case ANCHOR_SURFACE -> new Anchor.OnSurface(JsonTree.reqStr(m, KEY_NODE, path),
                    enumOf(Side.class, Side::parse, JsonTree.req(m, KEY_SIDE, path), JsonTree.child(path, KEY_SIDE)),
                    JsonTree.reqInt(m, KEY_U, path), JsonTree.reqInt(m, KEY_V, path));
            case ANCHOR_SLOT -> new Anchor.InSlot(JsonTree.reqStr(m, KEY_SLOT, path),
                    rotFromTree(m.get(KEY_ROT), JsonTree.child(path, KEY_ROT)));
            default -> throw JsonTree.bad(JsonTree.child(path, KEY_KIND), "unknown anchor kind \"" + kind + "\"");
        };
    }

    static Site siteFromTree(Object v, String path) {
        Map<String, Object> m = JsonTree.obj(v, path);
        int[] o = intsFromTree(JsonTree.req(m, KEY_ORIGIN, path), JsonTree.child(path, KEY_ORIGIN), ORIGIN_SIZE,
                ORIGIN_SHAPE);
        Facing facing = enumOf(Facing.class, Facing::parse, JsonTree.req(m, KEY_FACING, path),
                JsonTree.child(path, KEY_FACING));
        String terrain = JsonTree.optStr(m, KEY_TERRAIN_DIGEST, path);
        String claim = JsonTree.optStr(m, KEY_CLAIM_ID, path);
        return new Site(JsonTree.reqStr(m, KEY_DIMENSION, path), new BuildFrame(new IntPos(o[0], o[1], o[2]), facing),
                boxFromTree(JsonTree.req(m, KEY_BOUNDS, path), JsonTree.child(path, KEY_BOUNDS)), terrain, claim);
    }

    static StyleSpec styleFromTree(Object v, String path) {
        Map<String, Object> m = JsonTree.obj(v, path);
        Map<String, String> palette = new TreeMap<>();
        Map<String, Object> paletteTree = JsonTree.opt(m, KEY_PALETTE, path, JsonTree::obj);
        if (paletteTree != null) {
            String palettePath = JsonTree.child(path, KEY_PALETTE);
            for (Map.Entry<String, Object> e : paletteTree.entrySet()) {
                palette.put(e.getKey(), JsonTree.str(e.getValue(), JsonTree.child(palettePath, e.getKey())));
            }
        }
        Set<String> mood = new TreeSet<>(JsonTree.optList(m, KEY_MOOD_TAGS, path, JsonTree::str));
        return new StyleSpec(palette, mood);
    }

    public static PlanNode nodeFromTree(Object v, String path) {
        Map<String, Object> m = JsonTree.obj(v, path);
        Map<String, ParamValue> params = Objects.requireNonNullElse(
                JsonTree.opt(m, KEY_PARAMS, path, PlanJson::paramsFromTree), Map.of());
        Set<String> tags = new TreeSet<>(JsonTree.optList(m, KEY_TAGS, path, JsonTree::str));
        return new PlanNode(JsonTree.reqStr(m, KEY_ID, path), JsonTree.reqStr(m, KEY_TYPE, path),
                JsonTree.optStr(m, KEY_PARENT, path),
                anchorFromTree(JsonTree.req(m, KEY_ANCHOR, path), JsonTree.child(path, KEY_ANCHOR)), params, tags,
                JsonTree.optStr(m, KEY_LABEL, path));
    }

    /** {@code params} may be an object or, in the compact form, an array of {@code {key, value}} pairs. */
    static Map<String, ParamValue> paramsFromTree(Object v, String path) {
        Map<String, ParamValue> params = new TreeMap<>();
        if (v == null) {
            return params;
        }
        if (v instanceof List<?> pairs) {
            for (int i = 0; i < pairs.size(); i++) {
                String pairPath = JsonTree.item(path, i);
                Map<String, Object> pair = JsonTree.obj(pairs.get(i), pairPath);
                String key = JsonTree.str(JsonTree.req(pair, KEY_PAIR_KEY, pairPath),
                        JsonTree.child(pairPath, KEY_PAIR_KEY));
                if (params.containsKey(key)) {
                    throw JsonTree.bad(JsonTree.child(pairPath, KEY_PAIR_KEY), "duplicate key \"" + key + "\"");
                }
                params.put(key, paramFromTree(JsonTree.req(pair, KEY_PAIR_VALUE, pairPath),
                        JsonTree.child(pairPath, KEY_PAIR_VALUE)));
            }
            return params;
        }
        for (Map.Entry<String, Object> e : JsonTree.obj(v, path).entrySet()) {
            params.put(e.getKey(), paramFromTree(e.getValue(), JsonTree.child(path, e.getKey())));
        }
        return params;
    }

    static ParamValue paramFromTree(Object v, String path) {
        try {
            return ParamValue.fromTree(v);
        } catch (IllegalArgumentException e) {
            throw JsonTree.bad(path, e.getMessage());
        }
    }

    static PortRef portFromTree(Object v, String path) {
        Map<String, Object> m = JsonTree.obj(v, path);
        return new PortRef(JsonTree.reqStr(m, KEY_NODE, path), JsonTree.reqStr(m, KEY_PORT, path));
    }

    public static Connection connectionFromTree(Object v, String path) {
        Map<String, Object> m = JsonTree.obj(v, path);
        Routing routing = Objects.requireNonNullElse(JsonTree.opt(m, KEY_ROUTING, path, PlanJson::routingFromTree),
                Routing.AUTO);
        Constraints constraints = Objects.requireNonNullElse(
                JsonTree.opt(m, KEY_CONSTRAINTS, path, PlanJson::constraintsFromTree), Constraints.NONE);
        return new Connection(JsonTree.reqStr(m, KEY_ID, path),
                portFromTree(JsonTree.req(m, KEY_FROM, path), JsonTree.child(path, KEY_FROM)),
                portFromTree(JsonTree.req(m, KEY_TO, path), JsonTree.child(path, KEY_TO)),
                enumOf(ConnKind.class, ConnKind::parse, JsonTree.req(m, KEY_KIND, path), JsonTree.child(path, KEY_KIND)),
                routing, constraints);
    }

    static Routing routingFromTree(Object v, String path) {
        Map<String, Object> r = JsonTree.obj(v, path);
        String mode = JsonTree.reqStr(r, KEY_MODE, path);
        return switch (mode) {
            case ROUTING_EXPLICIT -> new Routing.Explicit(JsonTree.reqList(r, KEY_VIA, path, JsonTree::str));
            case ROUTING_AUTO -> Routing.AUTO;
            default -> throw JsonTree.bad(JsonTree.child(path, KEY_MODE), "unknown routing mode \"" + mode + "\"");
        };
    }

    static Constraints constraintsFromTree(Object v, String path) {
        Map<String, Object> c = JsonTree.obj(v, path);
        Integer maxLength = JsonTree.optInt(c, KEY_MAX_LENGTH, path);
        Integer maxTurns = JsonTree.optInt(c, KEY_MAX_TURNS, path);
        Set<String> avoid = new TreeSet<>(JsonTree.optList(c, KEY_AVOID, path, JsonTree::str));
        List<Dir6> dirList = JsonTree.optList(c, KEY_ENTRY_DIRS, path,
                (item, itemPath) -> enumOf(Dir6.class, Dir6::parse, item, itemPath));
        return new Constraints(maxLength, avoid, maxTurns, new TreeSet<>(dirList));
    }

    static LogisticsPlan logisticsFromTree(Object v, String path) {
        Map<String, Object> m = JsonTree.obj(v, path);
        return new LogisticsPlan(JsonTree.reqList(m, KEY_DOCKS, path, PlanJson::dockFromTree),
                JsonTree.reqList(m, KEY_ROUTES, path, PlanJson::routeFromTree),
                JsonTree.reqList(m, KEY_FLOWS, path, PlanJson::flowFromTree));
    }

    private static LogisticsPlan.Dock dockFromTree(Object v, String path) {
        Map<String, Object> d = JsonTree.obj(v, path);
        List<PortRef> ports = JsonTree.reqList(d, KEY_PORTS, path, PlanJson::portFromTree);
        List<String> connectors = JsonTree.reqList(d, KEY_CONNECTORS, path, JsonTree::str);
        return new LogisticsPlan.Dock(JsonTree.reqStr(d, KEY_ID, path),
                boxFromTree(JsonTree.req(d, KEY_PAD, path), JsonTree.child(path, KEY_PAD)),
                boxFromTree(JsonTree.req(d, KEY_CLEARANCE, path), JsonTree.child(path, KEY_CLEARANCE)),
                enumOf(Facing.class, Facing::parse, JsonTree.req(d, KEY_APPROACH, path),
                        JsonTree.child(path, KEY_APPROACH)),
                ports, connectors);
    }

    private static LogisticsPlan.Route routeFromTree(Object v, String path) {
        Map<String, Object> r = JsonTree.obj(v, path);
        List<LocalPos> waypoints = JsonTree.reqList(r, KEY_WAYPOINTS, path, PlanJson::posFromTree);
        return new LogisticsPlan.Route(JsonTree.reqStr(r, KEY_ID, path), JsonTree.reqStr(r, KEY_FROM, path),
                JsonTree.reqStr(r, KEY_TO, path), waypoints, JsonTree.optStr(r, KEY_AIRSHIP, path));
    }

    private static LogisticsPlan.CargoFlow flowFromTree(Object v, String path) {
        Map<String, Object> f = JsonTree.obj(v, path);
        return new LogisticsPlan.CargoFlow(JsonTree.reqStr(f, KEY_ITEM, path),
                JsonTree.number(JsonTree.req(f, KEY_PER_MIN, path), JsonTree.child(path, KEY_PER_MIN)),
                JsonTree.reqStr(f, KEY_FROM, path), JsonTree.reqStr(f, KEY_TO, path));
    }

    static Provenance provenanceFromTree(Object v, String path) {
        Map<String, Object> m = JsonTree.obj(v, path);
        List<String> images = JsonTree.optList(m, KEY_IMAGE_IDS, path, JsonTree::str);
        Double created = JsonTree.opt(m, KEY_CREATED_AT_MILLIS, path, JsonTree::number);
        return new Provenance(JsonTree.optStr(m, KEY_STAGE_ID, path), JsonTree.optStr(m, KEY_MODEL_ID, path),
                JsonTree.optStr(m, KEY_PROMPT_HASH, path), images,
                created == null ? Provenance.NONE.createdAtMillis() : created.longValue());
    }

    // ------------------------------------------------------------------ patch

    public static Map<String, Object> toTree(PlanPatch patch) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_PATCH_ID, patch.patchId());
        m.put(KEY_BASE_REVISION, patch.baseRevision());
        m.put(KEY_STAGE_ID, patch.stageId());
        m.put(KEY_OPS, trees(patch.ops(), PlanJson::opTree));
        return m;
    }

    private static Map<String, Object> opTree(PlanOp op) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (op) {
            case PlanOp.AddNode o -> {
                m.put(KEY_OP, OP_ADD_NODE);
                m.put(KEY_NODE, nodeToTree(o.node()));
            }
            case PlanOp.UpdateParams o -> {
                m.put(KEY_OP, OP_UPDATE_PARAMS);
                m.put(KEY_ID, o.id());
                m.put(KEY_PARAMS, paramsTree(o.params()));
            }
            case PlanOp.MoveNode o -> {
                m.put(KEY_OP, OP_MOVE_NODE);
                m.put(KEY_ID, o.id());
                m.put(KEY_ANCHOR, anchorTree(o.anchor()));
            }
            case PlanOp.RemoveNode o -> {
                m.put(KEY_OP, OP_REMOVE_NODE);
                m.put(KEY_ID, o.id());
            }
            case PlanOp.AddConnection o -> {
                m.put(KEY_OP, OP_ADD_CONNECTION);
                m.put(KEY_CONNECTION, connectionToTree(o.connection()));
            }
            case PlanOp.RemoveConnection o -> {
                m.put(KEY_OP, OP_REMOVE_CONNECTION);
                m.put(KEY_ID, o.id());
            }
            case PlanOp.SetStyle o -> {
                m.put(KEY_OP, OP_SET_STYLE);
                m.put(KEY_STYLE, styleTree(o.style()));
            }
            case PlanOp.SetSite o -> {
                m.put(KEY_OP, OP_SET_SITE);
                m.put(KEY_SITE, siteTree(o.site()));
            }
            case PlanOp.SetLogistics o -> {
                m.put(KEY_OP, OP_SET_LOGISTICS);
                m.put(KEY_LOGISTICS, o.logistics() == null ? null : logisticsTree(o.logistics()));
            }
        }
        return m;
    }

    public static PlanPatch patchFromTree(Object tree) {
        String path = JsonTree.ROOT_PATH;
        Map<String, Object> m = JsonTree.obj(tree, path);
        List<PlanOp> ops = JsonTree.reqList(m, KEY_OPS, path, PlanJson::opFromTree);
        String stage = Objects.requireNonNullElse(JsonTree.optStr(m, KEY_STAGE_ID, path), "");
        return new PlanPatch(JsonTree.reqStr(m, KEY_PATCH_ID, path), JsonTree.reqInt(m, KEY_BASE_REVISION, path), stage,
                ops);
    }

    private static PlanOp opFromTree(Object v, String path) {
        Map<String, Object> m = JsonTree.obj(v, path);
        String op = JsonTree.reqStr(m, KEY_OP, path);
        return switch (op) {
            case OP_ADD_NODE -> new PlanOp.AddNode(
                    nodeFromTree(JsonTree.req(m, KEY_NODE, path), JsonTree.child(path, KEY_NODE)));
            case OP_UPDATE_PARAMS -> {
                Map<String, ParamValue> params = paramsFromTree(JsonTree.req(m, KEY_PARAMS, path),
                        JsonTree.child(path, KEY_PARAMS));
                yield new PlanOp.UpdateParams(JsonTree.reqStr(m, KEY_ID, path), params);
            }
            case OP_MOVE_NODE -> new PlanOp.MoveNode(JsonTree.reqStr(m, KEY_ID, path),
                    anchorFromTree(JsonTree.req(m, KEY_ANCHOR, path), JsonTree.child(path, KEY_ANCHOR)));
            case OP_REMOVE_NODE -> new PlanOp.RemoveNode(JsonTree.reqStr(m, KEY_ID, path));
            case OP_ADD_CONNECTION -> new PlanOp.AddConnection(
                    connectionFromTree(JsonTree.req(m, KEY_CONNECTION, path), JsonTree.child(path, KEY_CONNECTION)));
            case OP_REMOVE_CONNECTION -> new PlanOp.RemoveConnection(JsonTree.reqStr(m, KEY_ID, path));
            case OP_SET_STYLE -> new PlanOp.SetStyle(
                    styleFromTree(JsonTree.req(m, KEY_STYLE, path), JsonTree.child(path, KEY_STYLE)));
            case OP_SET_SITE -> new PlanOp.SetSite(
                    siteFromTree(JsonTree.req(m, KEY_SITE, path), JsonTree.child(path, KEY_SITE)));
            case OP_SET_LOGISTICS -> new PlanOp.SetLogistics(
                    JsonTree.opt(m, KEY_LOGISTICS, path, PlanJson::logisticsFromTree));
            default -> throw JsonTree.bad(JsonTree.child(path, KEY_OP), "unknown operation \"" + op + "\"");
        };
    }
}
