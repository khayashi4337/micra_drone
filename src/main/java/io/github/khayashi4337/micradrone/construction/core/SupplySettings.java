package io.github.khayashi4337.micradrone.construction.core;

/**
 * One claim's supply switches (Task 27a). It holds a single flag: whether this claim's jobs may take
 * materials out of the owner's inventory. The default is off - a child's own belongings are spendable
 * only after an explicit per-claim decision, never implicitly.
 */
public record SupplySettings(boolean inventoryAllowed) {
    /** The switches every claim starts with. */
    public static final SupplySettings DEFAULT = new SupplySettings(false);
}
