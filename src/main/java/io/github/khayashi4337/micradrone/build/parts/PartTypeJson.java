package io.github.khayashi4337.micradrone.build.parts;

import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/** The tree form of a part type, used to compute the registry's version hash. Every field participates. */
public final class PartTypeJson {
    // Keys of the tree. They are hashed, so renaming one changes every registry version.
    private static final String KEY_ID = "id";
    private static final String KEY_CATEGORY = "category";
    private static final String KEY_VISIBILITY = "visibility";
    private static final String KEY_DISPLAY_NAME_KEY = "displayNameKey";
    private static final String KEY_VISUAL_DESCRIPTION = "visualDescription";
    private static final String KEY_PARAMS = "params";
    private static final String KEY_PORTS = "ports";
    private static final String KEY_VOLUME = "volume";
    private static final String KEY_REQUIRES = "requires";
    private static final String KEY_PLACER = "placer";
    private static final String KEY_VERIFY = "verify";
    private static final String KEY_VOLATILE_PROPS = "volatileProps";
    private static final String KEY_EFFECT = "effect";
    private static final String KEY_ASSEMBLY = "assembly";
    private static final String KEY_KINETIC_MODEL = "kineticModel";
    private static final String KEY_PHASE = "phase";

    private static final String KEY_NAME = "name";
    private static final String KEY_TYPE = "type";
    private static final String KEY_UNIT = "unit";
    private static final String KEY_MIN = "min";
    private static final String KEY_MAX = "max";
    private static final String KEY_DEFAULT = "default";
    private static final String KEY_ENUM_VALUES = "enumValues";
    private static final String KEY_MAX_ITEMS = "maxItems";

    private static final String KEY_KIND = "kind";
    private static final String KEY_OFFSET = "offset";
    private static final String KEY_FACING = "facing";
    private static final String KEY_ACCEPTS = "accepts";

    private static final String KEY_BOXES = "boxes";
    private static final String KEY_SIZE_FROM_PARAMS = "sizeFromParams";

    private static final String KEY_MOD_ID = "modId";
    private static final String KEY_RANGE = "range";

    private static final String KEY_REACH = "reach";

    private static final String KEY_TRIGGER_PORT = "triggerPort";
    private static final String KEY_DISASSEMBLE_ACTION = "disassembleAction";
    private static final String KEY_EXPECT = "expect";
    private static final String KEY_COUNT = "count";
    private static final String KEY_BLOCKS = "blocks";

    // Values that tell the two kinds of assembly expectation apart.
    private static final String EXPECT_CONTRAPTION = "contraption";
    private static final String EXPECT_SUBLEVEL = "sublevel";

    private PartTypeJson() {
    }

    public static Map<String, Object> toTree(PartType t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_ID, t.id());
        m.put(KEY_CATEGORY, t.category().name());
        m.put(KEY_VISIBILITY, t.visibility().name());
        m.put(KEY_DISPLAY_NAME_KEY, t.displayNameKey());
        m.put(KEY_VISUAL_DESCRIPTION, t.visualDescription());
        m.put(KEY_PARAMS, t.params().stream().map(PartTypeJson::paramTree).toList());
        m.put(KEY_PORTS, t.ports().stream().map(PartTypeJson::portTree).toList());
        m.put(KEY_VOLUME, volumeTree(t.volume()));
        m.put(KEY_REQUIRES, requiresTree(t.requires()));
        m.put(KEY_PLACER, t.placer().name());
        m.put(KEY_VERIFY, t.verify().name());
        m.put(KEY_VOLATILE_PROPS, new ArrayList<>(t.volatileProps()));
        m.put(KEY_EFFECT, effectTree(t.effect()));
        m.put(KEY_ASSEMBLY, nullable(t.assembly(), PartTypeJson::assemblyTree));
        m.put(KEY_KINETIC_MODEL, nullable(t.kineticModel(), ModelRef::modelId));
        m.put(KEY_PHASE, t.phase().name());
        return m;
    }

    /** Shared by part types and module templates, so both hash a version requirement the same way. */
    public static Map<String, Object> requiresTree(VersionRange r) {
        return Map.of(KEY_MOD_ID, r.modId(), KEY_RANGE, r.mavenRange());
    }

    public static Map<String, Object> portTree(PortSpec p) {
        Map<String, Object> pm = new LinkedHashMap<>();
        pm.put(KEY_NAME, p.name());
        pm.put(KEY_KIND, p.kind().name());
        pm.put(KEY_OFFSET, PlanJson.posTree(p.offset()));
        pm.put(KEY_FACING, p.facing().name());
        pm.put(KEY_ACCEPTS, new ArrayList<>(p.accepts()));
        return pm;
    }

    private static Map<String, Object> paramTree(ParamSpec p) {
        Map<String, Object> pm = new LinkedHashMap<>();
        pm.put(KEY_NAME, p.name());
        pm.put(KEY_TYPE, p.type().name());
        pm.put(KEY_UNIT, p.unit());
        pm.put(KEY_MIN, nullable(p.min(), ParamValue::toTree));
        pm.put(KEY_MAX, nullable(p.max(), ParamValue::toTree));
        pm.put(KEY_DEFAULT, nullable(p.defaultValue(), ParamValue::toTree));
        pm.put(KEY_ENUM_VALUES, new ArrayList<>(p.enumValues()));
        pm.put(KEY_MAX_ITEMS, p.maxItems());
        return pm;
    }

    /** A bound, default, reach, assembly or model may be absent (null); it is then null in the tree too. */
    private static <T> Object nullable(T value, Function<? super T, ?> toTree) {
        return value == null ? null : toTree.apply(value);
    }

    private static Map<String, Object> volumeTree(VolumeSpec v) {
        Map<String, Object> volume = new LinkedHashMap<>();
        volume.put(KEY_BOXES, v.boxes().stream().map(PlanJson::boxTree).toList());
        volume.put(KEY_SIZE_FROM_PARAMS, new TreeMap<>(v.sizeFromParams()));
        return volume;
    }

    private static Map<String, Object> effectTree(EffectSpec e) {
        Map<String, Object> effect = new LinkedHashMap<>();
        effect.put(KEY_KIND, e.kind().name());
        effect.put(KEY_REACH, nullable(e.reachLocal(), PlanJson::boxTree));
        return effect;
    }

    private static Map<String, Object> assemblyTree(AssemblySpec a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_KIND, a.kind().name());
        m.put(KEY_TRIGGER_PORT, a.triggerPort());
        m.put(KEY_DISASSEMBLE_ACTION, a.disassembleAction());
        if (a.expect() instanceof AssemblyExpectation.ContraptionExpectation c) {
            m.put(KEY_EXPECT, expectTree(EXPECT_CONTRAPTION, c.entityCount(), c.movedBlockCount()));
        } else if (a.expect() instanceof AssemblyExpectation.SubLevelExpectation s) {
            m.put(KEY_EXPECT, expectTree(EXPECT_SUBLEVEL, s.subLevelCount(), s.movedBlockCount()));
        } else {
            m.put(KEY_EXPECT, null);
        }
        return m;
    }

    private static Map<String, Object> expectTree(String type, int count, int movedBlocks) {
        return Map.of(KEY_TYPE, type, KEY_COUNT, count, KEY_BLOCKS, movedBlocks);
    }
}
