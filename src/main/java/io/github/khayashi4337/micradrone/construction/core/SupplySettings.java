package io.github.khayashi4337.micradrone.construction.core;

import java.util.Objects;
import java.util.Set;

/**
 * One claim's supply switches (Task 27a/27b): whether this claim's jobs may take materials out of
 * the owner's inventory (default off - a child's own belongings are spendable only after an
 * explicit per-claim decision), and the item ids the owner ruled out entirely - an excluded item
 * is not drawn on from any source, not even an allowed inventory.
 */
public record SupplySettings(boolean inventoryAllowed, Set<String> excludedItems) {
    /** How many item ids a claim's exclusion list may hold (the wire caps at the same count). */
    public static final int MAX_EXCLUDED_ITEMS = 32;

    /** The switches every claim starts with. */
    public static final SupplySettings DEFAULT = new SupplySettings(false, Set.of());

    public SupplySettings {
        Objects.requireNonNull(excludedItems, "excludedItems");
        if (excludedItems.size() > MAX_EXCLUDED_ITEMS) {
            throw new IllegalArgumentException(
                    "excludedItems over " + MAX_EXCLUDED_ITEMS + ": " + excludedItems.size());
        }
        excludedItems = Set.copyOf(excludedItems);
    }
}
