package io.github.khayashi4337.micradrone.drone;

import java.util.Map;

import io.github.khayashi4337.micradrone.MicraDrone;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Grants this mod's advancements (data/micradrone/advancement/*.json). {@code root} fires on its own
 * from a plain vanilla "obtained this item" trigger, but everything downstream of a shop purchase has
 * no vanilla-observable signal (purchaseUnlock never touches the player's inventory), so those use the
 * standard "minecraft:impossible" trigger + programmatic {@link net.minecraft.server.PlayerAdvancements#award}
 * pattern instead (verified against decompiled NeoForge 21.1.238 sources - see
 * ServerAdvancementManager#get / PlayerAdvancements#award).
 */
public final class MicraDroneAdvancements {
    private static final String CODE_TRIGGERED_CRITERION = "code_triggered";
    private static final Map<String, ResourceLocation> UNLOCK_ADVANCEMENTS = Map.of(
            "carrot", ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "unlock_carrot"),
            "pumpkin", ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "unlock_pumpkin"));

    /** Called from {@link DroneControllerBlockEntity#purchaseUnlock} right after a purchase succeeds. */
    public static void awardUnlock(ServerPlayer player, String unlockId) {
        ResourceLocation location = UNLOCK_ADVANCEMENTS.get(unlockId);
        if (location != null) {
            award(player, location);
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
