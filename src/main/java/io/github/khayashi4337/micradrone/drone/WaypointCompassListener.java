package io.github.khayashi4337.micradrone.drone;

import io.github.khayashi4337.micradrone.MicraDrone;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Binds a {@link WaypointCompassItem} to the player's current position - right-click anywhere,
 * block or open air, no special aiming required (a waypoint is "wherever I'm standing", not "the
 * block I'm looking at"). Instance-level {@code @SubscribeEvent} on a standalone object registered
 * from {@code MicraDrone}'s constructor - NOT a class-level {@code @EventBusSubscriber}, and NOT
 * static methods - matching {@code RegionPointerListener}'s documented real-machine crash finding
 * (a static {@code @SubscribeEvent} rejects the whole class at {@code EventBus.register}).
 *
 * <p>Two events, not one: {@code RightClickBlock} fires when a block is in reach,
 * {@code RightClickItem} fires instead when it isn't (open air) - see that class' javadoc ("NOT
 * fired if the player is targeting a block"). Binding needs both, since standing on solid ground
 * usually puts SOME block in reach.
 */
public final class WaypointCompassListener {

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!holdingCompass(event.getEntity(), event.getHand())) {
            return;
        }
        // Suppress the block's own interaction (e.g. don't also open a chest) and the item's
        // useOn - same two calls RegionPointerListener uses for the same reason.
        event.setUseBlock(TriState.FALSE);
        event.setUseItem(TriState.FALSE);
        bindIfServerMainHand(event.getEntity(), event.getHand(), event.getLevel().isClientSide());
    }

    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!holdingCompass(event.getEntity(), event.getHand())) {
            return;
        }
        event.setCanceled(true);
        bindIfServerMainHand(event.getEntity(), event.getHand(), event.getLevel().isClientSide());
    }

    private static boolean holdingCompass(Player player, InteractionHand hand) {
        return player.getItemInHand(hand).getItem() instanceof WaypointCompassItem;
    }

    /**
     * PlayerInteractEvent fires once per hand, on both the client and the (integrated or dedicated)
     * server. The bind itself must happen exactly once and be server-authoritative (it writes a
     * {@link WaypointData} component onto the stack, which then syncs to the client the normal
     * ItemStack way) - main-hand-only stops the double bind from off-hand also holding a compass,
     * server-only stops a client-side write that a dedicated server would never see.
     */
    private static void bindIfServerMainHand(Player player, InteractionHand hand, boolean isClientSide) {
        if (isClientSide || hand != InteractionHand.MAIN_HAND) {
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        stack.set(MicraDrone.WAYPOINT_DATA.get(), new WaypointData(player.blockPosition(), player.level().dimension()));
        player.displayClientMessage(Component.translatable("micradrone.waypoint_compass.bound",
                player.blockPosition().getX(), player.blockPosition().getY(), player.blockPosition().getZ()), true);
    }
}
