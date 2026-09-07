package io.github.khayashi4337.micradrone.drone;

import io.github.khayashi4337.micradrone.MicraDrone;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Binds a {@link WaypointCompassItem} to the player's current position - right-click anywhere,
 * block or open air, no special aiming required (a waypoint is "wherever I'm standing", not "the
 * block I'm looking at"). Instance-level {@code @SubscribeEvent} on a standalone object registered
 * from {@code MicraDrone}'s constructor - NOT a class-level {@code @EventBusSubscriber}, and NOT
 * static methods - matching {@code RegionPointerListener}'s documented real-machine crash finding
 * (a static {@code @SubscribeEvent} rejects the whole class at {@code EventBus.register}).
 *
 * <p>Two events, not one: {@code RightClickBlock} fires when a block is in reach, and - ONLY if
 * its result stays {@code PASS} - vanilla then tries {@code RightClickItem} on the very same click
 * (decompiled {@code Minecraft#startUseItem}/{@code MultiPlayerGameMode}: a block-target click that
 * doesn't consume the action falls through to the item-use path right after). Codex review finding
 * (confirmed bug): an earlier version left both events at their default {@code PASS} result (only
 * suppressing the block's OWN interaction via {@code setUseBlock/setUseItem(FALSE)}), so a
 * block-aimed click fired both handlers and bound the waypoint twice. Cancelling with a non-PASS
 * {@link InteractionResult#SUCCESS} is what actually tells vanilla "handled" - client and server
 * must report the SAME result or NeoForge logs a desync warning, so both sides set it identically
 * here rather than only the server-side branch that does the real write.
 */
public final class WaypointCompassListener {

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!holdingCompass(event.getEntity(), event.getHand())) {
            return;
        }
        event.setCanceled(true); // also sets useBlock/useItem to FALSE, suppressing the block's own interaction
        event.setCancellationResult(InteractionResult.SUCCESS); // non-PASS: stops the fall-through to RightClickItem below
        bindIfServerMainHand(event.getEntity(), event.getHand(), event.getLevel().isClientSide());
    }

    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!holdingCompass(event.getEntity(), event.getHand())) {
            return;
        }
        event.setCancellationResult(InteractionResult.SUCCESS); // non-PASS: stops the fall-through to the off hand
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
     * server-only stops a client-side write that a dedicated server would never see. Consuming the
     * action with a non-PASS result (see the two callers above) is what stops a SECOND, unwanted
     * server-side event (block-aimed clicks would otherwise fire both RightClickBlock AND
     * RightClickItem on the server) from calling this a second time for the same click.
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
