package io.github.khayashi4337.micradrone.construction.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The saved shape of a claim's {@link SupplySettings} (Task 27a/27b): one small tree per claim,
 * sealed in a {@link PersistenceEnvelope} of type {@link SaveTypes#SUPPLY} at version 1, stored at
 * {@code claims/<claimId>/supply.bin}. A missing or wrongly typed {@code inventoryAllowed} is an
 * {@link IllegalArgumentException}, never a guessed default; {@code excludedItems} (27b) decodes a
 * missing key as the empty set so a 27a file still reads - the version stays at 1.
 */
public final class SupplyCodec {
    private static final String KEY_INVENTORY = "inventoryAllowed";
    private static final String KEY_EXCLUDED = "excludedItems";

    private SupplyCodec() {
    }

    public static Object toTree(SupplySettings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_INVENTORY, s.inventoryAllowed());
        // sorted: the same settings must always write the same bytes
        m.put(KEY_EXCLUDED, new ArrayList<>(new java.util.TreeSet<>(s.excludedItems())));
        return m;
    }

    public static SupplySettings fromTree(Object tree) {
        Map<String, Object> m = JsonReads.map(tree, "supply settings");
        boolean allowed = JsonReads.bool(m.get(KEY_INVENTORY), KEY_INVENTORY);
        Object raw = m.get(KEY_EXCLUDED);
        if (raw == null) {
            return new SupplySettings(allowed, Set.of());
        }
        List<Object> entries = JsonReads.list(raw, KEY_EXCLUDED);
        Set<String> excluded = new LinkedHashSet<>(entries.size());
        for (Object entry : entries) {
            excluded.add(JsonReads.string(entry, KEY_EXCLUDED + "[]"));
        }
        return new SupplySettings(allowed, excluded);
    }
}
