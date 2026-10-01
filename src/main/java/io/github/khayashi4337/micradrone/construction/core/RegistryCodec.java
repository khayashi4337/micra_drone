package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A claim's placed-block registry as a tree (D-25): {@code {"claimId": s,
 * "placed": [[x,y,z,"placed","before","jobId",placementIndex,[earlierKeys...]]],
 * "assemblies": [[groupId, assembledId, moved, atTick]]}}. {@code earlierKeys} are the ledger keys the same job wrote
 * at the position before (Task 8: a removal settles every one of them).
 */
public final class RegistryCodec {
    private static final String KEY_CLAIM_ID = "claimId";
    private static final String KEY_PLACED = "placed";
    private static final String KEY_ASSEMBLIES = "assemblies";
    private static final int PLACED_FIELDS = 8;
    private static final int ASSEMBLY_FIELDS = 4;

    private RegistryCodec() {
    }

    public static Object toTree(PlacedRegistry registry) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_CLAIM_ID, registry.claimId());
        List<Object> placed = new ArrayList<>();
        for (Map.Entry<IntPos, PlacedEntry> e : registry.placed().entrySet()) {
            PlacedEntry p = e.getValue();
            List<Object> earlier = new ArrayList<>();
            for (int k : p.earlierKeys()) {
                earlier.add((long) k);
            }
            placed.add(List.of((long) e.getKey().x(), (long) e.getKey().y(), (long) e.getKey().z(),
                    p.placed().toString(), p.before().toString(), p.jobId(), (long) p.placementIndex(), earlier));
        }
        m.put(KEY_PLACED, placed);
        List<Object> assemblies = new ArrayList<>();
        for (AssemblyResult a : registry.assemblies().values()) {
            assemblies.add(List.of(a.groupId(), a.assembledId(), (long) a.movedBlockCount(), a.atTick()));
        }
        m.put(KEY_ASSEMBLIES, assemblies);
        return m;
    }

    public static PlacedRegistry fromTree(Object tree) {
        Map<String, Object> m = JsonReads.map(tree, "registry");
        PlacedRegistry registry = new PlacedRegistry(JsonReads.string(m.get(KEY_CLAIM_ID), KEY_CLAIM_ID));
        Map<IntPos, PlacedEntry> placed = new LinkedHashMap<>();
        for (Object o : JsonReads.list(m.get(KEY_PLACED), KEY_PLACED)) {
            List<Object> a = JsonReads.list(o, "placed entry");
            if (a.size() != PLACED_FIELDS) {
                throw new IllegalArgumentException("a placed entry has " + PLACED_FIELDS + " fields, not " + a.size());
            }
            List<Integer> earlier = new ArrayList<>();
            for (Object k : JsonReads.list(a.get(7), "earlier keys")) {
                earlier.add(JsonReads.integer(k, "earlier key"));
            }
            placed.put(new IntPos(JsonReads.integer(a.get(0), "x"), JsonReads.integer(a.get(1), "y"),
                            JsonReads.integer(a.get(2), "z")),
                    new PlacedEntry(BlockSpecText.parse(JsonReads.string(a.get(3), "placed")),
                            BlockSpecText.parse(JsonReads.string(a.get(4), "before")),
                            JsonReads.string(a.get(5), "jobId"), JsonReads.integer(a.get(6), "placementIndex"),
                            earlier));
        }
        registry.putAll(placed);
        for (Object o : JsonReads.list(m.get(KEY_ASSEMBLIES), KEY_ASSEMBLIES)) {
            List<Object> a = JsonReads.list(o, "assembly");
            if (a.size() != ASSEMBLY_FIELDS) {
                throw new IllegalArgumentException("an assembly has " + ASSEMBLY_FIELDS + " fields, not " + a.size());
            }
            registry.putAssembly(new AssemblyResult(JsonReads.string(a.get(0), "groupId"),
                    JsonReads.string(a.get(1), "assembledId"), JsonReads.integer(a.get(2), "moved"),
                    JsonReads.longValue(a.get(3), "atTick")));
        }
        return registry;
    }
}
