package io.github.khayashi4337.micradrone.construction.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The saved shape of a claim's {@link SupplySettings} (Task 27a): one small tree per claim, sealed in
 * a {@link PersistenceEnvelope} of type {@link SaveTypes#SUPPLY} at version 1, stored at
 * {@code claims/<claimId>/supply.bin}. A missing or wrongly typed key is an
 * {@link IllegalArgumentException}, never a guessed default.
 */
public final class SupplyCodec {
    private static final String KEY_INVENTORY = "inventoryAllowed";

    private SupplyCodec() {
    }

    public static Object toTree(SupplySettings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_INVENTORY, s.inventoryAllowed());
        return m;
    }

    public static SupplySettings fromTree(Object tree) {
        Map<String, Object> m = JsonReads.map(tree, "supply settings");
        return new SupplySettings(JsonReads.bool(m.get(KEY_INVENTORY), KEY_INVENTORY));
    }
}
