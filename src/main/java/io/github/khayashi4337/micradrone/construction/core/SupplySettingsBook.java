package io.github.khayashi4337.micradrone.construction.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Every claim's {@link SupplySettings} (Task 27a): {@code claimId -> SupplySettings}. Each claim's
 * switch is its own - one claim's yes is never another's - and a released claim's entry is removed
 * (the same release/rollback/cancel path the claim's other files take).
 */
public final class SupplySettingsBook {
    private final Map<String, SupplySettings> byClaim = new LinkedHashMap<>();

    /**
     * Sets only the claim's inventory switch, keeping its exclusions (27b): the "use my things?"
     * answer must not silently drop the item list the owner already ruled out.
     */
    public void allowInventory(String claimId, boolean allowed) {
        byClaim.put(Objects.requireNonNull(claimId, "claimId"),
                new SupplySettings(allowed, of(claimId).excludedItems()));
    }

    /** Replaces the claim's whole settings; a change of an already-set claim is an ordinary write. */
    public void set(String claimId, SupplySettings settings) {
        byClaim.put(Objects.requireNonNull(claimId, "claimId"),
                Objects.requireNonNull(settings, "settings"));
    }

    /** The claim's switch; claims that were never set keep {@link SupplySettings#DEFAULT} (off). */
    public boolean inventoryAllowed(String claimId) {
        return of(claimId).inventoryAllowed();
    }

    /** The claim's whole entry; {@link SupplySettings#DEFAULT} when it was never set. */
    public SupplySettings of(String claimId) {
        return byClaim.getOrDefault(claimId, SupplySettings.DEFAULT);
    }

    /** Drops the claim's settings: a released claim keeps nothing, not even a yes. */
    public void remove(String claimId) {
        byClaim.remove(claimId);
    }

    /** The live entries (claimId -> settings), for saving and the change digest. Read-only. */
    public Map<String, SupplySettings> entries() {
        return Collections.unmodifiableMap(byClaim);
    }
}
