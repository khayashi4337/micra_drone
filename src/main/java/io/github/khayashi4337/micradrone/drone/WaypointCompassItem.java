package io.github.khayashi4337.micradrone.drone;

import java.util.List;

import io.github.khayashi4337.micradrone.MicraDrone;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * Like a vanilla lodestone compass, but bindable to any spot by right-clicking there - no block
 * has to be placed at the destination. Deliberately featureless as an {@code Item} - the binding
 * happens in {@code WaypointCompassListener} via {@code PlayerInteractEvent}, and the live
 * direction/distance display in {@code WaypointCompassRenderer}, the same
 * "Item stays thin, NeoForge event bus does the work" pattern {@link RegionPointerItem} already
 * uses (see that class' javadoc for why: two past real-machine bugs from {@code useOn}/
 * {@code useWithoutItem} dispatch-order assumptions, neither of which an event-bus listener is
 * exposed to).
 */
public class WaypointCompassItem extends Item {
    public WaypointCompassItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        WaypointData data = stack.get(MicraDrone.WAYPOINT_DATA.get());
        tooltip.add(Component.translatable(data == null
                ? "item.micradrone.waypoint_compass.tooltip_unbound"
                : "item.micradrone.waypoint_compass.tooltip_bound"));
    }
}
