package io.github.khayashi4337.micradrone.build.parts;

import io.github.khayashi4337.micradrone.build.model.CanonicalJson;
import io.github.khayashi4337.micradrone.build.model.ConnKind;
import io.github.khayashi4337.micradrone.build.model.Dir6;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.plan.PlanIds;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the {@code --json-schema} for a PlanPatch from the part registry (P-16), so the AI, the checker and the
 * compiler share one vocabulary. Two forms. TYPED: per-part parameter types, ranges and enums (via oneOf), with
 * shared pieces in $defs. FLAT: the compact form for the cmd.exe route (5,000 characters): parameters as
 * key/value pairs, and the rarely used sub-objects (logistics, constraints) and the parameter names only loosely
 * typed. In every form the schema shapes the output; PlanJson, PlanPatcher and PlanCompiler check everything
 * again (E-PARAM-RANGE etc.).
 */
public final class SchemaGenerator {
    /** TYPED: one branch per part, with parameter types. FLAT: the compact form for the tight budget. */
    public enum Mode { TYPED, FLAT }

    /** A built schema and its canonical JSON text; {@link #chars} is the compact-JSON length S-1 measures. */
    public record Generated(Mode mode, Map<String, Object> schema, String json) {
        public Generated(Mode mode, Map<String, Object> schema, String json) {
            this.mode = mode;
            this.schema = freeze(schema);
            this.json = json;
        }

        public int chars() {
            return json.length();
        }
    }

    private static final String ROLE_BODY = "[a-z][a-z0-9_]*";
    private static final String BLOCK_ID_BODY = "[a-z0-9_.-]+:[a-z0-9_/.-]+";
    /** The id form shared with PlanIds: 1 to {@link PlanIds#MAX_LENGTH} of lowercase letters, digits, hyphens. */
    public static final String ID_PATTERN = "^[a-z0-9-]{1," + PlanIds.MAX_LENGTH + "}$";
    /** A palette role name (the same form {@link ParamValidator#isRoleName} accepts). */
    public static final String ROLE_PATTERN = "^" + ROLE_BODY + "$";
    /** A material is a palette role or a block id: the same union ParamValidator accepts. */
    public static final String MATERIAL_PATTERN = "^(" + ROLE_BODY + "|" + BLOCK_ID_BODY + ")$";

    // JSON Schema keywords and the values of "type".
    private static final String K_REF = "$ref";
    private static final String K_DEFS = "$defs";
    private static final String K_TYPE = "type";
    private static final String K_PROPERTIES = "properties";
    private static final String K_REQUIRED = "required";
    private static final String K_ADDITIONAL_PROPERTIES = "additionalProperties";
    private static final String K_ONE_OF = "oneOf";
    private static final String K_CONST = "const";
    private static final String K_ENUM = "enum";
    private static final String K_MINIMUM = "minimum";
    private static final String K_MAXIMUM = "maximum";
    private static final String K_MAX_LENGTH = "maxLength";
    private static final String K_PATTERN = "pattern";
    private static final String K_ITEMS = "items";
    private static final String K_MIN_ITEMS = "minItems";
    private static final String K_MAX_ITEMS = "maxItems";

    private static final String T_OBJECT = "object";
    private static final String T_ARRAY = "array";
    private static final String T_STRING = "string";
    private static final String T_INTEGER = "integer";
    private static final String T_NUMBER = "number";
    private static final String T_BOOLEAN = "boolean";
    private static final String T_NULL = "null";

    // $defs names; where a def name is also a property key ("anchor", "site", ...) the constant serves both.
    private static final String DEF_POS = "pos";
    private static final String DEF_ROT = "rot";
    private static final String DEF_ANCHOR = "anchor";
    private static final String DEF_CONNECTION = "connection";
    private static final String DEF_SITE = "site";
    private static final String DEF_STYLE = "style";
    private static final String DEF_LOGISTICS = "logistics";
    private static final String DEF_ANY_PARAMS = "anyParams";
    private static final String DEF_ROUTING = "routing";
    private static final String DEF_CONSTRAINTS = "constraints";
    private static final String DEF_MATERIAL = "material";
    private static final String DEF_DIR4 = "dir4";

    // Property keys that occur in more than one fragment.
    private static final String KEY_OPS = "ops";
    private static final String KEY_OP = "op";
    private static final String KEY_ID = "id";
    private static final String KEY_NODE = "node";
    private static final String KEY_PORT = "port";
    private static final String KEY_PARAMS = "params";
    private static final String KEY_FROM = "from";
    private static final String KEY_TO = "to";
    private static final String KEY_KIND = "kind";
    private static final String KEY_KEY = "key";
    private static final String KEY_VALUE = "value";
    private static final String KEY_MODE = "mode";
    private static final String KEY_VIA = "via";

    private static final String REF_PREFIX = "#/$defs/";
    private static final List<String> DIR4 = Arrays.stream(Facing.values()).map(Facing::lower).toList();
    private static final List<String> DIR6_ALL = Arrays.stream(Dir6.values()).map(Dir6::lower).toList();
    private static final List<String> CONN_KINDS = Arrays.stream(ConnKind.values()).map(ConnKind::lower).toList();
    private static final List<String> SURFACE_SIDES = List.of(Side.OUTER.lower(), Side.INNER.lower());

    /** A position is [u, v, w]; a site origin is [x, y, z]. */
    private static final int POS_ITEMS = 3;
    /** A box is six integers [minA, minB, minC, maxA, maxB, maxC]. */
    private static final int BOX_ITEMS = 6;
    private static final int MIN_QUARTER_TURNS = 0;
    private static final int MAX_QUARTER_TURNS = 3;
    /** A position on a wall face counts from the wall's corner, so it is never negative. */
    private static final int MIN_SURFACE_INDEX = 0;

    private SchemaGenerator() {
    }

    /** The first form that fits {@code maxChars}: TYPED if it fits, else FLAT, else a SchemaTooLargeException. */
    public static Generated forLimit(PartTypeRegistry registry, Set<String> partIdsOrNull, int maxChars) {
        Map<Mode, Integer> sizes = new EnumMap<>(Mode.class);
        for (Mode mode : Mode.values()) {
            Map<String, Object> schema = patchSchema(registry, mode, partIdsOrNull);
            String json = CanonicalJson.write(schema);
            if (json.length() <= maxChars) {
                return new Generated(mode, schema, json);
            }
            sizes.put(mode, json.length());
        }
        throw new SchemaTooLargeException("the schema does not fit " + maxChars + " characters even in the flat form"
                + " (typed: " + sizes.get(Mode.TYPED) + ", flat: " + sizes.get(Mode.FLAT) + ")");
    }

    /**
     * The schema of a {@code PlanPatch} body ({@code {"ops": [...]}}). {@code partIdsOrNull} limits the offered
     * parts; null offers every USER part. IMPLICIT parts are never offered. Map keys are written in insertion
     * order; CanonicalJson sorts them for the JSON text.
     */
    public static Map<String, Object> patchSchema(PartTypeRegistry registry, Mode mode, Set<String> partIdsOrNull) {
        boolean flat = mode == Mode.FLAT;
        List<PartType> parts = new ArrayList<>();
        for (PartType t : registry.userParts()) {
            if (partIdsOrNull == null || partIdsOrNull.contains(t.id())) {
                parts.add(t);
            }
        }
        if (parts.isEmpty()) {
            // A node's "type" is a oneOf over the offered parts; zero branches is not a usable schema.
            throw new IllegalArgumentException("partIdsOrNull leaves no USER part to offer");
        }
        // userParts() iterates the registry's id-ordered TreeMap, so the schema needs no sort of its own.

        Map<String, Object> defs = new LinkedHashMap<>();
        defs.put(DEF_POS, array(map(K_TYPE, T_INTEGER), POS_ITEMS, POS_ITEMS));
        defs.put(DEF_ROT, obj(List.of(), map("turns",
                map(K_TYPE, T_INTEGER, K_MINIMUM, MIN_QUARTER_TURNS, K_MAXIMUM, MAX_QUARTER_TURNS),
                "mirror", map(K_TYPE, T_BOOLEAN))));
        defs.put(DEF_ANCHOR, anchorSchema());
        defs.put(DEF_CONNECTION, connectionSchema(flat));
        defs.put(DEF_SITE, siteSchema());
        defs.put(DEF_STYLE, obj(List.of("palette"), map("palette",
                map(K_TYPE, T_OBJECT, K_ADDITIONAL_PROPERTIES, map(K_TYPE, T_STRING)),
                "moodTags", map(K_TYPE, T_ARRAY, K_ITEMS, map(K_TYPE, T_STRING)))));
        defs.put(DEF_LOGISTICS, flat ? map(K_TYPE, T_OBJECT) : logisticsSchema());
        // ajv strictTypes (checked with the real claude CLI on 2026-09-26) warns on a "type" array of several
        // non-null members and a stricter CLI could turn that into an error, so "any value" is emitted as the
        // unconstrained schema {}; PlanJson/ParamValidator check the actual types after parsing.
        defs.put(DEF_ANY_PARAMS, flat ? flatParams() : map(K_TYPE, T_OBJECT, K_ADDITIONAL_PROPERTIES, Map.of()));
        defs.put(DEF_ROUTING, routingSchema());
        defs.put(DEF_CONSTRAINTS, flat ? map(K_TYPE, T_OBJECT) : constraintsSchema());
        if (!flat) {
            defs.put(DEF_MATERIAL, map(K_TYPE, T_STRING, K_PATTERN, MATERIAL_PATTERN));
            defs.put(DEF_DIR4, map(K_TYPE, T_STRING, K_ENUM, DIR4));
        }

        List<Object> ops = new ArrayList<>();
        ops.add(op("add_node", List.of(KEY_NODE), map(KEY_NODE, nodeSchema(parts, flat))));
        ops.add(op("update_params", List.of(KEY_ID, KEY_PARAMS),
                map(KEY_ID, map(K_TYPE, T_STRING), KEY_PARAMS, ref(DEF_ANY_PARAMS))));
        ops.add(op("move_node", List.of(KEY_ID, DEF_ANCHOR),
                map(KEY_ID, map(K_TYPE, T_STRING), DEF_ANCHOR, ref(DEF_ANCHOR))));
        ops.add(op("remove_node", List.of(KEY_ID), map(KEY_ID, map(K_TYPE, T_STRING))));
        ops.add(op("add_connection", List.of(DEF_CONNECTION), map(DEF_CONNECTION, ref(DEF_CONNECTION))));
        ops.add(op("remove_connection", List.of(KEY_ID), map(KEY_ID, map(K_TYPE, T_STRING))));
        ops.add(op("set_style", List.of(DEF_STYLE), map(DEF_STYLE, ref(DEF_STYLE))));
        ops.add(op("set_site", List.of(DEF_SITE), map(DEF_SITE, ref(DEF_SITE))));
        ops.add(op("set_logistics", List.of(DEF_LOGISTICS),
                map(DEF_LOGISTICS, map(K_ONE_OF, List.of(ref(DEF_LOGISTICS), map(K_TYPE, T_NULL))))));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put(K_TYPE, T_OBJECT);
        root.put(K_ADDITIONAL_PROPERTIES, false);
        root.put(K_REQUIRED, List.of(KEY_OPS));
        root.put(K_PROPERTIES, map(KEY_OPS, map(K_TYPE, T_ARRAY, K_ITEMS, map(K_ONE_OF, ops))));
        root.put(K_DEFS, defs);
        return root;
    }

    private static Map<String, Object> nodeSchema(List<PartType> parts, boolean flat) {
        if (flat) {
            List<String> ids = new ArrayList<>();
            for (PartType t : parts) {
                ids.add(t.id());
            }
            return nodeObject(map(K_TYPE, T_STRING, K_ENUM, ids), ref(DEF_ANY_PARAMS), true);
        }
        List<Object> perPart = new ArrayList<>();
        for (PartType t : parts) {
            perPart.add(nodeObject(map(K_CONST, t.id()), typedParams(t), false));
        }
        return map(K_ONE_OF, perPart);
    }

    private static Map<String, Object> nodeObject(Object typeSchema, Object paramsSchema, boolean flat) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(KEY_ID, flat ? map(K_TYPE, T_STRING) : map(K_TYPE, T_STRING, K_PATTERN, ID_PATTERN));
        props.put(K_TYPE, typeSchema);
        props.put("parent", map(K_TYPE, List.of(T_STRING, T_NULL)));
        props.put(DEF_ANCHOR, ref(DEF_ANCHOR));
        props.put(KEY_PARAMS, paramsSchema);
        props.put("tags", map(K_TYPE, T_ARRAY, K_ITEMS, map(K_TYPE, T_STRING)));
        props.put("label", map(K_TYPE, T_STRING));
        return obj(List.of(KEY_ID, K_TYPE, DEF_ANCHOR, KEY_PARAMS), props);
    }

    private static Map<String, Object> typedParams(PartType t) {
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (ParamSpec p : t.params()) {
            props.put(p.name(), paramSchema(p));
            if (p.required()) {
                required.add(p.name());
            }
        }
        return obj(required, props);
    }

    private static Map<String, Object> paramSchema(ParamSpec p) {
        return switch (p.type()) {
            case INT -> ranged(T_INTEGER, p.min(), p.max());
            case NUM -> ranged(T_NUMBER, p.min(), p.max());
            case BOOL -> map(K_TYPE, T_BOOLEAN);
            case STR -> {
                Map<String, Object> s = map(K_TYPE, T_STRING);
                if (p.max() != null) {
                    s.put(K_MAX_LENGTH, p.max().toTree());
                }
                yield s;
            }
            case ENUM -> p.enumValues().equals(DIR4) ? ref(DEF_DIR4) : map(K_TYPE, T_STRING, K_ENUM, p.enumValues());
            case MATERIAL -> ref(DEF_MATERIAL);
            case INT_LIST -> {
                Map<String, Object> s = map(K_TYPE, T_ARRAY, K_ITEMS, ranged(T_INTEGER, p.min(), p.max()));
                if (p.maxItems() > 0) {
                    s.put(K_MAX_ITEMS, p.maxItems());
                }
                yield s;
            }
        };
    }

    private static Map<String, Object> ranged(String type, ParamValue min, ParamValue max) {
        Map<String, Object> s = map(K_TYPE, type);
        if (min != null) {
            s.put(K_MINIMUM, min.toTree());
        }
        if (max != null) {
            s.put(K_MAXIMUM, max.toTree());
        }
        return s;
    }

    /** The compact form: the name is a free string (PlanPatcher rejects unknown names), the value is unconstrained. */
    private static Map<String, Object> flatParams() {
        // The value {} accepts anything, for the same strictTypes reason as the typed anyParams (2026-09-26).
        Map<String, Object> pair = obj(List.of(KEY_KEY, KEY_VALUE), map(KEY_KEY, map(K_TYPE, T_STRING),
                KEY_VALUE, Map.of()));
        return map(K_TYPE, T_ARRAY, K_ITEMS, pair);
    }

    private static Map<String, Object> anchorSchema() {
        Map<String, Object> absolute = obj(List.of(KEY_KIND, DEF_POS), map(KEY_KIND, map(K_CONST, "absolute"),
                DEF_POS, ref(DEF_POS), DEF_ROT, ref(DEF_ROT)));
        Map<String, Object> surface = obj(List.of(KEY_KIND, KEY_NODE, "side", "u", "v"),
                map(KEY_KIND, map(K_CONST, "surface"), KEY_NODE, map(K_TYPE, T_STRING),
                        "side", map(K_TYPE, T_STRING, K_ENUM, SURFACE_SIDES),
                        "u", map(K_TYPE, T_INTEGER, K_MINIMUM, MIN_SURFACE_INDEX),
                        "v", map(K_TYPE, T_INTEGER, K_MINIMUM, MIN_SURFACE_INDEX)));
        Map<String, Object> slot = obj(List.of(KEY_KIND, "slot"), map(KEY_KIND, map(K_CONST, "slot"),
                "slot", map(K_TYPE, T_STRING), DEF_ROT, ref(DEF_ROT)));
        return map(K_ONE_OF, List.of(absolute, surface, slot));
    }

    private static Map<String, Object> connectionSchema(boolean flat) {
        Map<String, Object> port = portSchema();
        return obj(List.of(KEY_ID, KEY_FROM, KEY_TO, KEY_KIND),
                map(KEY_ID, flat ? map(K_TYPE, T_STRING) : map(K_TYPE, T_STRING, K_PATTERN, ID_PATTERN),
                        KEY_FROM, port, KEY_TO, port,
                        KEY_KIND, map(K_TYPE, T_STRING, K_ENUM, CONN_KINDS),
                        DEF_ROUTING, ref(DEF_ROUTING), DEF_CONSTRAINTS, ref(DEF_CONSTRAINTS)));
    }

    /** Discriminated by {@code mode}: {@code auto} takes no other member, {@code explicit} needs {@code via}. */
    private static Map<String, Object> routingSchema() {
        Map<String, Object> auto = obj(List.of(KEY_MODE), map(KEY_MODE, map(K_CONST, "auto")));
        Map<String, Object> explicit = obj(List.of(KEY_MODE, KEY_VIA), map(KEY_MODE, map(K_CONST, "explicit"),
                KEY_VIA, map(K_TYPE, T_ARRAY, K_ITEMS, map(K_TYPE, T_STRING))));
        return map(K_ONE_OF, List.of(auto, explicit));
    }

    private static Map<String, Object> constraintsSchema() {
        return obj(List.of(),
                map(K_MAX_LENGTH, map(K_TYPE, List.of(T_INTEGER, T_NULL)),
                        "avoid", map(K_TYPE, T_ARRAY, K_ITEMS, map(K_TYPE, T_STRING)),
                        "maxTurns", map(K_TYPE, List.of(T_INTEGER, T_NULL)),
                        "entryDirs", map(K_TYPE, T_ARRAY, K_ITEMS, map(K_TYPE, T_STRING, K_ENUM, DIR6_ALL))));
    }

    private static Map<String, Object> siteSchema() {
        return obj(List.of("dimension", "origin", "facing", "bounds"),
                map("dimension", map(K_TYPE, T_STRING),
                        "origin", array(map(K_TYPE, T_INTEGER), POS_ITEMS, POS_ITEMS),
                        "facing", map(K_TYPE, T_STRING, K_ENUM, DIR4),
                        "bounds", array(map(K_TYPE, T_INTEGER), BOX_ITEMS, BOX_ITEMS),
                        "terrainDigest", map(K_TYPE, T_STRING), "claimId", map(K_TYPE, T_STRING)));
    }

    private static Map<String, Object> logisticsSchema() {
        Map<String, Object> box = array(map(K_TYPE, T_INTEGER), BOX_ITEMS, BOX_ITEMS);
        Map<String, Object> dock = obj(List.of(KEY_ID, "pad", "clearance", "approach", "ports", "connectors"),
                map(KEY_ID, map(K_TYPE, T_STRING), "pad", box, "clearance", box,
                        "approach", map(K_TYPE, T_STRING, K_ENUM, DIR4),
                        "ports", map(K_TYPE, T_ARRAY, K_ITEMS, portSchema()),
                        "connectors", map(K_TYPE, T_ARRAY, K_ITEMS, map(K_TYPE, T_STRING))));
        Map<String, Object> route = obj(List.of(KEY_ID, KEY_FROM, KEY_TO, "waypoints"),
                map(KEY_ID, map(K_TYPE, T_STRING), KEY_FROM, map(K_TYPE, T_STRING), KEY_TO, map(K_TYPE, T_STRING),
                        "waypoints", map(K_TYPE, T_ARRAY, K_ITEMS, ref(DEF_POS)),
                        "airship", map(K_TYPE, List.of(T_STRING, T_NULL))));
        Map<String, Object> flow = obj(List.of("item", "perMin", KEY_FROM, KEY_TO),
                map("item", map(K_TYPE, T_STRING), "perMin", map(K_TYPE, T_NUMBER),
                        KEY_FROM, map(K_TYPE, T_STRING), KEY_TO, map(K_TYPE, T_STRING)));
        return obj(List.of("docks", "routes", "flows"),
                map("docks", map(K_TYPE, T_ARRAY, K_ITEMS, dock),
                        "routes", map(K_TYPE, T_ARRAY, K_ITEMS, route),
                        "flows", map(K_TYPE, T_ARRAY, K_ITEMS, flow)));
    }

    private static Map<String, Object> portSchema() {
        return obj(List.of(KEY_NODE, KEY_PORT), map(KEY_NODE, map(K_TYPE, T_STRING), KEY_PORT, map(K_TYPE, T_STRING)));
    }

    private static Map<String, Object> op(String name, List<String> required, Map<String, Object> fields) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(KEY_OP, map(K_CONST, name));
        props.putAll(fields);
        List<String> req = new ArrayList<>(List.of(KEY_OP));
        req.addAll(required);
        return obj(req, props);
    }

    private static Map<String, Object> obj(List<String> required, Map<String, Object> properties) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(K_TYPE, T_OBJECT);
        m.put(K_ADDITIONAL_PROPERTIES, false);
        if (!required.isEmpty()) {
            m.put(K_REQUIRED, required);
        }
        m.put(K_PROPERTIES, properties);
        return m;
    }

    private static Map<String, Object> array(Map<String, Object> items, int min, int max) {
        return map(K_TYPE, T_ARRAY, K_ITEMS, items, K_MIN_ITEMS, min, K_MAX_ITEMS, max);
    }

    private static Map<String, Object> ref(String name) {
        return map(K_REF, REF_PREFIX + name);
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    /** A deep read-only copy, so a {@link Generated} record cannot leak the builder's mutable tree. */
    private static Map<String, Object> freeze(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            out.put(e.getKey(), freezeValue(e.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }

    private static Object freezeValue(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put((String) e.getKey(), freezeValue(e.getValue()));
            }
            return Collections.unmodifiableMap(out);
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object item : l) {
                out.add(freezeValue(item));
            }
            return Collections.unmodifiableList(out);
        }
        return v;
    }
}
