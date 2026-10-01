package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * A job's material ledger as a tree: the amounts of each kind by ledger key, and the newest write-ahead-log run folded
 * in. {@code {"consumed": {"key": [["item", n], ...]}, "returned": {...}, "yielded": {...}, "reclaimed": {...},
 * "appliedRun": n}} (JSON object keys are strings).
 */
public final class LedgerCodec {
    static final String KEY_APPLIED_RUN = "appliedRun";

    private LedgerCodec() {
    }

    private static String keyOf(MaterialOp op) {
        return switch (op) {
            case CHARGE -> "consumed";
            case RETURN -> "returned";
            case YIELD -> "yielded";
            case RECLAIM -> "reclaimed";
        };
    }

    private static SortedMap<Integer, List<ItemCount>> view(MaterialLedger l, MaterialOp op) {
        return switch (op) {
            case CHARGE -> l.consumedView();
            case RETURN -> l.returnedView();
            case YIELD -> l.yieldedView();
            case RECLAIM -> l.reclaimedView();
        };
    }

    public static Object toTree(MaterialLedger ledger) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (MaterialOp op : MaterialOp.values()) {
            Map<String, Object> byKey = new LinkedHashMap<>();
            view(ledger, op).forEach((key, items) -> byKey.put(Integer.toString(key), itemsTree(items)));
            out.put(keyOf(op), byKey);
        }
        out.put(KEY_APPLIED_RUN, ledger.appliedRun());
        return out;
    }

    public static MaterialLedger fromTree(Object tree) {
        Map<String, Object> m = JsonReads.map(tree, "ledger");
        MaterialLedger l = new MaterialLedger();
        for (MaterialOp op : MaterialOp.values()) {
            for (Map.Entry<String, Object> e : JsonReads.map(m.get(keyOf(op)), keyOf(op)).entrySet()) {
                l.replay(op, Integer.parseInt(e.getKey()), itemsFromTree(e.getValue()));
            }
        }
        long applied = JsonReads.longValue(m.get(KEY_APPLIED_RUN), KEY_APPLIED_RUN);
        if (applied != MaterialLedger.NO_RUN) {
            l.markAppliedRun(applied);
        }
        return l;
    }

    static List<Object> itemsTree(List<ItemCount> items) {
        List<Object> out = new ArrayList<>();
        for (ItemCount c : items) {
            out.add(List.of(c.itemId(), (long) c.count()));
        }
        return out;
    }

    static List<ItemCount> itemsFromTree(Object o) {
        List<ItemCount> out = new ArrayList<>();
        for (Object x : JsonReads.list(o, "items")) {
            List<Object> a = JsonReads.list(x, "item count");
            out.add(new ItemCount(JsonReads.string(a.get(0), "item"), JsonReads.integer(a.get(1), "count")));
        }
        return out;
    }
}
