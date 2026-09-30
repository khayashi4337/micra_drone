package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.CanonicalJson;
import io.github.khayashi4337.micradrone.build.model.Hashing;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.PlanJson;
import io.github.khayashi4337.micradrone.build.parts.AssemblyExpectation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Tree form of a manifest and its hash. The hash covers what is built, not how it is labelled (no index, no node ids). */
public final class ManifestJson {
    // Keys of the trees. Most are hashed, so renaming one changes every manifest hash.
    private static final String KEY_POS = "pos";
    private static final String KEY_BLOCK = "block";
    private static final String KEY_ID = "id";
    private static final String KEY_PROPS = "props";
    private static final String KEY_BLOCK_ENTITY = "be";
    private static final String KEY_PHASE = "phase";
    private static final String KEY_PLACER = "placer";
    private static final String KEY_VERIFY = "verify";
    private static final String KEY_REPLACES = "replaces";
    private static final String KEY_GROUP = "group";
    private static final String KEY_INDEX = "index";
    private static final String KEY_NODE = "node";
    private static final String KEY_KIND = "kind";
    private static final String KEY_TRIGGER = "trigger";
    private static final String KEY_MEMBERS = "members";
    private static final String KEY_EXPECT = "expect";
    private static final String KEY_TYPE = "type";
    private static final String KEY_COUNT = "count";
    private static final String KEY_BLOCKS = "blocks";
    private static final String KEY_DIMENSION = "dimension";
    private static final String KEY_REGISTRY_VERSION = "registryVersion";
    private static final String KEY_WORLD_BOUNDS = "worldBounds";
    private static final String KEY_PLACEMENTS = "placements";
    private static final String KEY_ASSEMBLIES = "assemblies";
    private static final String KEY_BOM = "bom";
    private static final String KEY_MANIFEST_VERSION = "manifestVersion";
    private static final String KEY_PLAN_ID = "planId";
    private static final String KEY_PLAN_REVISION = "planRevision";
    private static final String KEY_FRAME = "frame";
    private static final String KEY_ORIGIN = "origin";
    private static final String KEY_FACING = "facing";
    private static final String KEY_PHASES = "phases";
    private static final String KEY_FROM = "from";
    private static final String KEY_TO = "to";
    private static final String KEY_HASH = "hash";
    private static final String EXPECT_CONTRAPTION = "contraption";
    private static final String EXPECT_SUBLEVEL = "sublevel";

    private ManifestJson() {
    }

    private static List<Object> posTree(IntPos p) {
        return new ArrayList<>(List.of((long) p.x(), (long) p.y(), (long) p.z()));
    }

    static Map<String, Object> placementTree(Placement p, boolean forHash) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_POS, posTree(p.pos()));
        Map<String, Object> block = new LinkedHashMap<>();
        block.put(KEY_ID, p.block().blockId());
        block.put(KEY_PROPS, new TreeMap<>(p.block().properties()));
        m.put(KEY_BLOCK, block);
        m.put(KEY_BLOCK_ENTITY, new TreeMap<>(p.blockEntityConfig()));
        m.put(KEY_PHASE, p.phase().name());
        m.put(KEY_PLACER, p.placer().name());
        m.put(KEY_VERIFY, p.verify().name());
        m.put(KEY_REPLACES, p.replaces().code());
        m.put(KEY_GROUP, p.assemblyGroup());
        if (!forHash) {
            m.put(KEY_INDEX, p.index());
            m.put(KEY_NODE, p.partNodeId());
        }
        return m;
    }

    static Map<String, Object> assemblyTree(AssemblyStep a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_GROUP, a.groupId());
        m.put(KEY_KIND, a.kind().name());
        m.put(KEY_TRIGGER, posTree(a.trigger()));
        m.put(KEY_MEMBERS, new ArrayList<>(a.memberIndexes()));
        if (a.expect() instanceof AssemblyExpectation.ContraptionExpectation c) {
            m.put(KEY_EXPECT, Map.of(KEY_TYPE, EXPECT_CONTRAPTION, KEY_COUNT, c.entityCount(), KEY_BLOCKS, c.movedBlockCount()));
        } else if (a.expect() instanceof AssemblyExpectation.SubLevelExpectation s) {
            m.put(KEY_EXPECT, Map.of(KEY_TYPE, EXPECT_SUBLEVEL, KEY_COUNT, s.subLevelCount(), KEY_BLOCKS, s.movedBlockCount()));
        } else {
            m.put(KEY_EXPECT, null);
        }
        return m;
    }

    private static List<Object> placementTrees(List<Placement> placements, boolean forHash) {
        List<Object> out = new ArrayList<>();
        for (Placement p : placements) {
            out.add(placementTree(p, forHash));
        }
        return out;
    }

    private static List<Object> assemblyTrees(List<AssemblyStep> assemblies) {
        List<Object> out = new ArrayList<>();
        for (AssemblyStep a : assemblies) {
            out.add(assemblyTree(a));
        }
        return out;
    }

    public static Map<String, Object> hashTree(String dimension, String registryVersion, Box worldBounds, List<Placement> placements,
                                               List<AssemblyStep> assemblies, Map<String, Integer> bom) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_DIMENSION, dimension);
        m.put(KEY_REGISTRY_VERSION, registryVersion);
        m.put(KEY_WORLD_BOUNDS, PlanJson.boxTree(worldBounds));
        m.put(KEY_PLACEMENTS, placementTrees(placements, true));
        m.put(KEY_ASSEMBLIES, assemblyTrees(assemblies));
        m.put(KEY_BOM, new TreeMap<>(bom));
        return m;
    }

    public static String computeHash(String dimension, String registryVersion, Box worldBounds, List<Placement> placements,
                                     List<AssemblyStep> assemblies, Map<String, Integer> bom) {
        return Hashing.sha256Hex(CanonicalJson.write(hashTree(dimension, registryVersion, worldBounds, placements, assemblies, bom)));
    }

    public static Map<String, Object> toTree(PlacementManifest m) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put(KEY_MANIFEST_VERSION, m.manifestVersion());
        t.put(KEY_PLAN_ID, m.planId());
        t.put(KEY_PLAN_REVISION, m.planRevision());
        t.put(KEY_REGISTRY_VERSION, m.registryVersion());
        t.put(KEY_DIMENSION, m.dimension());
        t.put(KEY_FRAME, Map.of(KEY_ORIGIN, posTree(m.frame().origin()), KEY_FACING, m.frame().facing().lower()));
        t.put(KEY_WORLD_BOUNDS, PlanJson.boxTree(m.worldBounds()));
        t.put(KEY_PLACEMENTS, placementTrees(m.placements(), false));
        t.put(KEY_ASSEMBLIES, assemblyTrees(m.assemblies()));
        t.put(KEY_BOM, new TreeMap<>(m.bom()));
        List<Object> phases = new ArrayList<>();
        for (PhaseRange r : m.phases()) {
            phases.add(Map.of(KEY_PHASE, r.phase().name(), KEY_FROM, r.fromIndex(), KEY_TO, r.toIndexExclusive()));
        }
        t.put(KEY_PHASES, phases);
        t.put(KEY_HASH, m.hash());
        return t;
    }
}
