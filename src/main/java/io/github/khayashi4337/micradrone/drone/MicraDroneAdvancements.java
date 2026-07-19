package io.github.khayashi4337.micradrone.drone;

import java.util.Map;
import java.util.Set;

import io.github.khayashi4337.micradrone.MicraDrone;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Grants this mod's advancements (data/micradrone/advancement/*.json). {@code root}/{@code
 * corner_marker} fire on their own from plain vanilla "obtained this item" triggers, but everything
 * else has no vanilla-observable signal (purchaseUnlock never touches the player's inventory, and
 * harvested crops never drop items either - see LiveFarmBlockAccess), so those use the standard
 * "minecraft:impossible" trigger + programmatic {@link net.minecraft.server.PlayerAdvancements#award}
 * pattern instead (verified against decompiled NeoForge 21.1.238 sources - see
 * ServerAdvancementManager#get / PlayerAdvancements#award).
 */
public final class MicraDroneAdvancements {
    private static final String CODE_TRIGGERED_CRITERION = "code_triggered";
    private static final Map<String, ResourceLocation> UNLOCK_ADVANCEMENTS = Map.of(
            "carrot", ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "unlock_carrot"),
            "pumpkin", ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "unlock_pumpkin"));

    // Mirrors the original game's 3-tier "Farmer / Big Farmer / Master" achievement structure per
    // resource (see thefarmerwasreplaced Steam achievements), minus the rate-based "Master" tier -
    // that needs a sliding time window this mod doesn't track yet. Thresholds are scaled way down
    // from the original's 1000/1e8-1e9 (balanced around that game's late-game automation speed) to
    // something reachable at Minecraft's one-block-at-a-time pace.
    private static final long[] HARVEST_TIERS = {10, 100, 1000};
    private static final Set<String> TIERED_CROPS = Set.of("wheat", "carrot", "pumpkin");

    /** Called from {@link DroneControllerBlockEntity#purchaseUnlock} right after a purchase succeeds. */
    public static void awardUnlock(ServerPlayer player, String unlockId) {
        ResourceLocation location = UNLOCK_ADVANCEMENTS.get(unlockId);
        if (location != null) {
            award(player, location);
        }
    }

    /**
     * Called from {@link DroneControllerBlockEntity#addPoints} after each harvest. Awards every tier
     * threshold {@code oldTotal} hadn't reached yet but {@code newTotal} has - a single big jump (e.g.
     * a giant pumpkin patch's lump-sum bonus) can cross more than one tier at once, so every tier is
     * checked rather than just the nearest one.
     */
    public static void checkHarvestMilestones(ServerPlayer player, String crop, long oldTotal, long newTotal) {
        if (!TIERED_CROPS.contains(crop)) {
            return;
        }
        for (long tier : HARVEST_TIERS) {
            if (oldTotal < tier && newTotal >= tier) {
                ResourceLocation location = ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "harvest_" + crop + "_" + tier);
                award(player, location);
            }
        }
    }

    private static void award(ServerPlayer player, ResourceLocation location) {
        AdvancementHolder holder = player.server.getAdvancements().get(location);
        if (holder != null) {
            player.getAdvancements().award(holder, CODE_TRIGGERED_CRITERION);
        }
    }

    private MicraDroneAdvancements() {
    }
}
