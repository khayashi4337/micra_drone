package io.github.khayashi4337.micradrone.drone;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure catalog + afford-check logic for the point-spending unlock shop, kept Minecraft-free so it can
 * be unit tested without a real world. "wheat" isn't listed here - it's unlocked on every plot from
 * the start (see {@link DroneControllerBlockEntity}). Public: the catalog is static/identical on both
 * sides, so DroneScreen's Shop tab reads it directly instead of it being sent over the network.
 */
public final class UnlockShop {
    public record Unlock(String id, Map<String, Long> cost) {}

    public static final List<Unlock> CATALOG = List.of(
            new Unlock("carrot", Map.of("wheat", 20L)),
            new Unlock("pumpkin", Map.of("wheat", 30L, "carrot", 15L)));

    private UnlockShop() {
    }

    public static Optional<Unlock> find(String id) {
        return CATALOG.stream().filter(u -> u.id().equals(id)).findFirst();
    }

    /** True if points has at least the required amount of every crop cost lists. */
    public static boolean canAfford(Map<String, Long> points, Map<String, Long> cost) {
        return cost.entrySet().stream().allMatch(entry -> points.getOrDefault(entry.getKey(), 0L) >= entry.getValue());
    }
}
