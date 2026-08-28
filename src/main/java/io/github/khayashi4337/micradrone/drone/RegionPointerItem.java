package io.github.khayashi4337.micradrone.drone;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * The AI chat's WorldEdit-style region-reference wand: left-click a block to set the start corner,
 * right-click another to set the end corner. Deliberately featureless as an {@code Item} - all the
 * actual corner-picking happens in {@code RegionPointerListener} (client package) via
 * {@code NeoForge.EVENT_BUS}'s {@code PlayerInteractEvent.LeftClickBlock}/{@code RightClickBlock},
 * not an {@code Item#useOn} override, since SPK-3 (docs/investigations/spk3_left_click_block_event.md)
 * found those events fire client-side regardless of block/item interaction dispatch order - the
 * same two bug classes this project hit twice before with {@code useOn}/{@code useWithoutItem}
 * ordering never apply here.
 */
public class RegionPointerItem extends Item {
    public RegionPointerItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.micradrone.region_pointer.tooltip"));
    }
}
