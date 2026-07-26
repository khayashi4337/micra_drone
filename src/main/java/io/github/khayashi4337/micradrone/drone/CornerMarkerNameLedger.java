package io.github.khayashi4337.micradrone.drone;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * World-wide friendly-name uniqueness bookkeeping for Corner Markers (林さんの「同名のID指定は弾く」
 * requirement) - a plain name-&gt;position map with claim/release operations. Deliberately kept
 * Minecraft-free (plain {@link MarkerPos} ints, no {@code BlockPos}) - the test sourceSet has no
 * access to Minecraft classes, same discipline {@code PlotGeometry} uses.
 * {@link CornerMarkerNameRegistry} is the Minecraft-side persistence wrapper that converts to/from
 * {@code BlockPos} at its own boundary.
 */
public final class CornerMarkerNameLedger {
    /** A plain (x, y, z) triple - the Minecraft-free stand-in for BlockPos in this pure logic. */
    public record MarkerPos(int x, int y, int z) {
    }

    private final Map<String, MarkerPos> ownerByName = new HashMap<>();

    /**
     * Claims {@code name} for {@code claimant}. Succeeds if the name is free, or already owned by
     * this exact position (re-claiming your own name is a no-op success). Fails if another position
     * already owns it.
     */
    public boolean tryClaim(String name, MarkerPos claimant) {
        MarkerPos existing = ownerByName.get(name);
        if (existing != null && !existing.equals(claimant)) {
            return false;
        }
        ownerByName.put(name, claimant);
        return true;
    }

    /** Frees {@code name}, but only if {@code owner} is the position that currently holds it. */
    public void release(String name, MarkerPos owner) {
        ownerByName.computeIfPresent(name, (n, pos) -> pos.equals(owner) ? null : pos);
    }

    public Optional<MarkerPos> ownerOf(String name) {
        return Optional.ofNullable(ownerByName.get(name));
    }

    public Map<String, MarkerPos> asMap() {
        return Map.copyOf(ownerByName);
    }

    public static CornerMarkerNameLedger fromMap(Map<String, MarkerPos> entries) {
        CornerMarkerNameLedger ledger = new CornerMarkerNameLedger();
        ledger.ownerByName.putAll(entries);
        return ledger;
    }
}
