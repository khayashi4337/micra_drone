package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A job program as a tree: {@code {"restores": [[x,y,z,"expectedNow",[volatile...],"restoreTo","sourceJobId",
 * sourceLedgerKey,dropContents,[earlierSourceKeys...]]], "puts": [[index, ledgerKey], ...]}}. A placement step stores
 * only the manifest index and its ledger key: the manifest is saved once already (04 F-2), so duplicating the
 * placements would save the same bytes twice. {@link #fromTree} takes the manifest to resolve the indexes.
 */
public final class ProgramCodec {
    private static final String KEY_RESTORES = "restores";
    private static final String KEY_PUTS = "puts";
    private static final int RESTORE_FIELDS = 10;
    private static final int PUT_FIELDS = 2;

    private ProgramCodec() {
    }

    public static Object toTree(JobProgram p) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> restores = new ArrayList<>();
        for (RestoreItem r : p.restores()) {
            restores.add(restoreTree(r));
        }
        m.put(KEY_RESTORES, restores);
        m.put(KEY_PUTS, putsTree(p.puts()));
        return m;
    }

    public static JobProgram fromTree(Object tree, PlacementManifest manifest) {
        Map<String, Object> m = JsonReads.map(tree, "program");
        List<RestoreItem> restores = new ArrayList<>();
        for (Object o : JsonReads.list(m.get(KEY_RESTORES), KEY_RESTORES)) {
            restores.add(restoreFromTree(o));
        }
        return new JobProgram(restores, putsFromTree(m.get(KEY_PUTS), manifest));
    }

    /** A repair round's put list keeps its own ledger keys (they differ from the manifest indexes). */
    public static Object repairToTree(List<PutItem> puts) {
        return putsTree(puts);
    }

    public static List<PutItem> repairFromTree(Object tree, PlacementManifest manifest) {
        return putsFromTree(tree, manifest);
    }

    private static List<Object> restoreTree(RestoreItem r) {
        List<Object> volatileProps = new ArrayList<>(r.volatileProps());
        volatileProps.sort(null);
        List<Object> earlier = new ArrayList<>();
        for (int k : r.earlierSourceKeys()) {
            earlier.add((long) k);
        }
        return List.of((long) r.pos().x(), (long) r.pos().y(), (long) r.pos().z(), r.expectedNow().toString(),
                volatileProps, r.restoreTo().toString(), r.sourceJobId(), (long) r.sourceLedgerKey(),
                r.dropContents(), earlier);
    }

    private static RestoreItem restoreFromTree(Object o) {
        List<Object> a = JsonReads.list(o, "restore");
        if (a.size() != RESTORE_FIELDS) {
            throw new IllegalArgumentException("a restore has " + RESTORE_FIELDS + " fields, not " + a.size());
        }
        Set<String> volatileProps = new HashSet<>();
        for (Object v : JsonReads.list(a.get(4), "volatile properties")) {
            volatileProps.add(JsonReads.string(v, "volatile property"));
        }
        List<Integer> earlier = new ArrayList<>();
        for (Object k : JsonReads.list(a.get(9), "earlier source keys")) {
            earlier.add(JsonReads.integer(k, "earlier source key"));
        }
        return new RestoreItem(
                new IntPos(JsonReads.integer(a.get(0), "x"), JsonReads.integer(a.get(1), "y"),
                        JsonReads.integer(a.get(2), "z")),
                BlockSpecText.parse(JsonReads.string(a.get(3), "expectedNow")), volatileProps,
                BlockSpecText.parse(JsonReads.string(a.get(5), "restoreTo")),
                JsonReads.string(a.get(6), "sourceJobId"), JsonReads.integer(a.get(7), "sourceLedgerKey"),
                JsonReads.bool(a.get(8), "dropContents"), earlier);
    }

    private static List<Object> putsTree(List<PutItem> puts) {
        List<Object> out = new ArrayList<>();
        for (PutItem p : puts) {
            out.add(List.of((long) p.index(), (long) p.ledgerKey()));
        }
        return out;
    }

    private static List<PutItem> putsFromTree(Object tree, PlacementManifest manifest) {
        List<PutItem> out = new ArrayList<>();
        for (Object o : JsonReads.list(tree, KEY_PUTS)) {
            List<Object> a = JsonReads.list(o, "put");
            if (a.size() != PUT_FIELDS) {
                throw new IllegalArgumentException("a put has " + PUT_FIELDS + " fields, not " + a.size());
            }
            int index = JsonReads.integer(a.get(0), "index");
            if (index < 0 || index >= manifest.placements().size()) {
                throw new IllegalArgumentException("put index " + index + " outside the manifest's 0.."
                        + (manifest.placements().size() - 1));
            }
            out.add(new PutItem(index, JsonReads.integer(a.get(1), "ledgerKey"), manifest.placements().get(index)));
        }
        return out;
    }
}
