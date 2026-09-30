package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.AssemblyStep;
import io.github.khayashi4337.micradrone.build.compile.BomCalculator;
import io.github.khayashi4337.micradrone.build.compile.ManifestJson;
import io.github.khayashi4337.micradrone.build.compile.PhaseRanges;
import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.ReplacePolicy;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.parts.AssemblyExpectation;
import io.github.khayashi4337.micradrone.build.parts.AssemblyKind;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.PlacerId;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A manifest as a palette-compressed tree (04 F-2): each distinct {@link io.github.khayashi4337.micradrone.build.model
 * .BlockSpec BlockSpec} text is stored once in {@code "palette"} and each placement points at it by number (238
 * placements share a few dozen block states). The node ids are compressed the same way. On load the bill of materials
 * and the hash are recomputed from the placements and compared with the stored values; a manifest that does not
 * reproduce its hash is refused (the job goes recovery-pending instead of trusting the bytes).
 */
public final class ManifestCodec {
    private static final String KEY_MANIFEST_VERSION = "manifestVersion";
    private static final String KEY_PLAN_ID = "planId";
    private static final String KEY_PLAN_REVISION = "planRevision";
    private static final String KEY_REGISTRY_VERSION = "registryVersion";
    private static final String KEY_DIMENSION = "dimension";
    private static final String KEY_FRAME = "frame";
    private static final String KEY_ORIGIN = "origin";
    private static final String KEY_FACING = "facing";
    private static final String KEY_WORLD_BOUNDS = "worldBounds";
    private static final String KEY_PALETTE = "palette";
    private static final String KEY_NODES = "nodes";
    private static final String KEY_PLACEMENTS = "placements";
    private static final String KEY_ASSEMBLIES = "assemblies";
    private static final String KEY_BOM = "bom";
    private static final String KEY_HASH = "hash";
    private static final String KEY_NODE_TYPES = "nodeTypes";
    private static final String KEY_GROUP = "group";
    private static final String KEY_KIND = "kind";
    private static final String KEY_TRIGGER = "trigger";
    private static final String KEY_MEMBERS = "members";
    private static final String KEY_EXPECT = "expect";
    private static final String KEY_TYPE = "type";
    private static final String KEY_COUNT = "count";
    private static final String KEY_BLOCKS = "blocks";
    private static final String EXPECT_CONTRAPTION = "contraption";
    private static final String EXPECT_SUBLEVEL = "sublevel";
    private static final int PLACEMENT_FIELDS = 11;
    private static final int POS_FIELDS = 3;
    private static final int BOX_FIELDS = 6;

    /** A manifest and the part-node types of its plan (the types are not hashed: they label, they do not build). */
    public record Stored(PlacementManifest manifest, Map<String, String> nodeTypes) {
        public Stored {
            Objects.requireNonNull(manifest, "manifest");
            nodeTypes = Map.copyOf(Objects.requireNonNull(nodeTypes, "nodeTypes"));
        }
    }

    private ManifestCodec() {
    }

    public static Object toTree(Stored stored) {
        PlacementManifest m = stored.manifest();
        Map<String, Integer> paletteIndex = new HashMap<>();
        List<Object> palette = new ArrayList<>();
        Map<String, Integer> nodeIndex = new HashMap<>();
        List<Object> nodes = new ArrayList<>();
        List<Object> placements = new ArrayList<>();
        for (Placement p : m.placements()) {
            List<Object> a = new ArrayList<>();
            a.add((long) p.pos().x());
            a.add((long) p.pos().y());
            a.add((long) p.pos().z());
            a.add((long) intern(p.block().toString(), palette, paletteIndex));
            a.add((long) intern(p.partNodeId(), nodes, nodeIndex));
            a.add(p.phase().name());
            a.add(p.placer().name());
            a.add(p.verify().name());
            a.add(p.replaces().code());
            a.add(p.assemblyGroup());
            a.add(new TreeMap<>(p.blockEntityConfig()));
            placements.add(a);
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put(KEY_MANIFEST_VERSION, (long) m.manifestVersion());
        t.put(KEY_PLAN_ID, m.planId());
        t.put(KEY_PLAN_REVISION, (long) m.planRevision());
        t.put(KEY_REGISTRY_VERSION, m.registryVersion());
        t.put(KEY_DIMENSION, m.dimension());
        t.put(KEY_FRAME, Map.of(KEY_ORIGIN, posTree(m.frame().origin()), KEY_FACING, m.frame().facing().lower()));
        t.put(KEY_WORLD_BOUNDS, boxTree(m.worldBounds()));
        t.put(KEY_PALETTE, palette);
        t.put(KEY_NODES, nodes);
        t.put(KEY_PLACEMENTS, placements);
        List<Object> assemblies = new ArrayList<>();
        for (AssemblyStep a : m.assemblies()) {
            assemblies.add(assemblyTree(a));
        }
        t.put(KEY_ASSEMBLIES, assemblies);
        t.put(KEY_BOM, bomTree(m.bom()));
        t.put(KEY_HASH, m.hash());
        t.put(KEY_NODE_TYPES, new TreeMap<>(stored.nodeTypes()));
        return t;
    }

    public static Stored fromTree(Object tree) {
        Map<String, Object> t = JsonReads.map(tree, "manifest");
        List<String> palette = new ArrayList<>();
        for (Object o : JsonReads.list(t.get(KEY_PALETTE), KEY_PALETTE)) {
            palette.add(JsonReads.string(o, "palette entry"));
        }
        List<String> nodes = new ArrayList<>();
        for (Object o : JsonReads.list(t.get(KEY_NODES), KEY_NODES)) {
            nodes.add(JsonReads.stringOrNull(o, "node id"));
        }
        List<Placement> placements = new ArrayList<>();
        for (Object o : JsonReads.list(t.get(KEY_PLACEMENTS), KEY_PLACEMENTS)) {
            placements.add(placementFromTree(o, placements.size(), palette, nodes));
        }
        List<AssemblyStep> assemblies = new ArrayList<>();
        for (Object o : JsonReads.list(t.get(KEY_ASSEMBLIES), KEY_ASSEMBLIES)) {
            assemblies.add(assemblyFromTree(o));
        }
        Map<String, Integer> bom = bomFromTree(t.get(KEY_BOM));
        String dimension = JsonReads.string(t.get(KEY_DIMENSION), KEY_DIMENSION);
        String registryVersion = JsonReads.string(t.get(KEY_REGISTRY_VERSION), KEY_REGISTRY_VERSION);
        Box worldBounds = boxFromTree(t.get(KEY_WORLD_BOUNDS));
        if (!BomCalculator.bom(placements).equals(bom)) {
            throw new IllegalArgumentException("stored manifest does not reproduce its hash");
        }
        String hash = ManifestJson.computeHash(dimension, registryVersion, worldBounds, placements, assemblies, bom);
        if (!hash.equals(JsonReads.string(t.get(KEY_HASH), KEY_HASH))) {
            throw new IllegalArgumentException("stored manifest does not reproduce its hash");
        }
        Map<String, Object> frame = JsonReads.map(t.get(KEY_FRAME), KEY_FRAME);
        PlacementManifest m = new PlacementManifest(JsonReads.integer(t.get(KEY_MANIFEST_VERSION), KEY_MANIFEST_VERSION),
                JsonReads.string(t.get(KEY_PLAN_ID), KEY_PLAN_ID),
                JsonReads.integer(t.get(KEY_PLAN_REVISION), KEY_PLAN_REVISION), registryVersion, dimension,
                new BuildFrame(posFromTree(frame.get(KEY_ORIGIN)), Facing.parse(JsonReads.string(frame.get(KEY_FACING), KEY_FACING))),
                worldBounds, placements, assemblies, bom, PhaseRanges.of(placements), hash);
        Map<String, String> nodeTypes = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : JsonReads.map(t.get(KEY_NODE_TYPES), KEY_NODE_TYPES).entrySet()) {
            nodeTypes.put(e.getKey(), JsonReads.string(e.getValue(), "node type"));
        }
        return new Stored(m, nodeTypes);
    }

    private static int intern(String key, List<Object> list, Map<String, Integer> index) {
        Integer i = index.get(key);
        if (i == null) {
            i = list.size();
            list.add(key);
            index.put(key, i);
        }
        return i;
    }

    private static Placement placementFromTree(Object o, int index, List<String> palette, List<String> nodes) {
        List<Object> a = JsonReads.list(o, "placement");
        if (a.size() != PLACEMENT_FIELDS) {
            throw new IllegalArgumentException("a placement has " + PLACEMENT_FIELDS + " fields, not " + a.size());
        }
        IntPos pos = new IntPos(JsonReads.integer(a.get(0), "x"), JsonReads.integer(a.get(1), "y"),
                JsonReads.integer(a.get(2), "z"));
        int pi = JsonReads.integer(a.get(3), "palette index");
        int ni = JsonReads.integer(a.get(4), "node index");
        if (pi < 0 || pi >= palette.size()) {
            throw new IllegalArgumentException("palette index " + pi + " outside 0.." + (palette.size() - 1));
        }
        if (ni < 0 || ni >= nodes.size()) {
            throw new IllegalArgumentException("node index " + ni + " outside 0.." + (nodes.size() - 1));
        }
        Map<String, String> beConfig = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : JsonReads.map(a.get(10), "block entity config").entrySet()) {
            beConfig.put(e.getKey(), JsonReads.string(e.getValue(), "block entity config value"));
        }
        return new Placement(index, pos, BlockSpecText.parse(palette.get(pi)), beConfig, nodes.get(ni),
                BuildPhase.valueOf(JsonReads.string(a.get(5), "phase")),
                PlacerId.valueOf(JsonReads.string(a.get(6), "placer")),
                VerifyMode.valueOf(JsonReads.string(a.get(7), "verify")),
                ReplacePolicy.fromCode(JsonReads.string(a.get(8), "replaces")),
                JsonReads.stringOrNull(a.get(9), "group"));
    }

    private static Map<String, Object> assemblyTree(AssemblyStep a) {
        // the same shape as ManifestJson's assembly tree (its writer is package-private, so the form is repeated here:
        // the hash covers it, so the shapes must agree)
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_GROUP, a.groupId());
        m.put(KEY_KIND, a.kind().name());
        m.put(KEY_TRIGGER, posTree(a.trigger()));
        List<Object> members = new ArrayList<>();
        for (int i : a.memberIndexes()) {
            members.add((long) i);
        }
        m.put(KEY_MEMBERS, members);
        if (a.expect() instanceof AssemblyExpectation.ContraptionExpectation c) {
            m.put(KEY_EXPECT, Map.of(KEY_TYPE, EXPECT_CONTRAPTION, KEY_COUNT, c.entityCount(), KEY_BLOCKS, c.movedBlockCount()));
        } else if (a.expect() instanceof AssemblyExpectation.SubLevelExpectation s) {
            m.put(KEY_EXPECT, Map.of(KEY_TYPE, EXPECT_SUBLEVEL, KEY_COUNT, s.subLevelCount(), KEY_BLOCKS, s.movedBlockCount()));
        } else {
            m.put(KEY_EXPECT, null);
        }
        return m;
    }

    private static AssemblyStep assemblyFromTree(Object o) {
        Map<String, Object> m = JsonReads.map(o, "assembly");
        List<Integer> members = new ArrayList<>();
        for (Object i : JsonReads.list(m.get(KEY_MEMBERS), KEY_MEMBERS)) {
            members.add(JsonReads.integer(i, "member index"));
        }
        AssemblyExpectation expect = null;
        Object e = m.get(KEY_EXPECT);
        if (e != null) {
            Map<String, Object> em = JsonReads.map(e, KEY_EXPECT);
            int count = JsonReads.integer(em.get(KEY_COUNT), KEY_COUNT);
            int blocks = JsonReads.integer(em.get(KEY_BLOCKS), KEY_BLOCKS);
            expect = switch (JsonReads.string(em.get(KEY_TYPE), KEY_TYPE)) {
                case EXPECT_CONTRAPTION -> new AssemblyExpectation.ContraptionExpectation(count, blocks);
                case EXPECT_SUBLEVEL -> new AssemblyExpectation.SubLevelExpectation(count, blocks);
                default -> throw new IllegalArgumentException("unknown assembly expectation: " + em.get(KEY_TYPE));
            };
        }
        return new AssemblyStep(JsonReads.string(m.get(KEY_GROUP), KEY_GROUP),
                AssemblyKind.valueOf(JsonReads.string(m.get(KEY_KIND), KEY_KIND)),
                posFromTree(m.get(KEY_TRIGGER)), members, expect);
    }

    private static Map<String, Object> bomTree(Map<String, Integer> bom) {
        Map<String, Object> out = new TreeMap<>();
        bom.forEach((id, n) -> out.put(id, (long) n));
        return out;
    }

    private static Map<String, Integer> bomFromTree(Object o) {
        Map<String, Integer> out = new TreeMap<>();
        for (Map.Entry<String, Object> e : JsonReads.map(o, KEY_BOM).entrySet()) {
            out.put(e.getKey(), JsonReads.integer(e.getValue(), "bom count"));
        }
        return out;
    }

    private static List<Object> posTree(IntPos p) {
        return List.of((long) p.x(), (long) p.y(), (long) p.z());
    }

    private static IntPos posFromTree(Object o) {
        List<Object> a = JsonReads.list(o, "position");
        if (a.size() != POS_FIELDS) {
            throw new IllegalArgumentException("a position has " + POS_FIELDS + " fields, not " + a.size());
        }
        return new IntPos(JsonReads.integer(a.get(0), "x"), JsonReads.integer(a.get(1), "y"),
                JsonReads.integer(a.get(2), "z"));
    }

    private static List<Object> boxTree(Box b) {
        return List.of((long) b.minA(), (long) b.minB(), (long) b.minC(), (long) b.maxA(), (long) b.maxB(), (long) b.maxC());
    }

    private static Box boxFromTree(Object o) {
        List<Object> a = JsonReads.list(o, "box");
        if (a.size() != BOX_FIELDS) {
            throw new IllegalArgumentException("a box has " + BOX_FIELDS + " fields, not " + a.size());
        }
        return new Box(JsonReads.integer(a.get(0), "minA"), JsonReads.integer(a.get(1), "minB"),
                JsonReads.integer(a.get(2), "minC"), JsonReads.integer(a.get(3), "maxA"),
                JsonReads.integer(a.get(4), "maxB"), JsonReads.integer(a.get(5), "maxC"));
    }
}
