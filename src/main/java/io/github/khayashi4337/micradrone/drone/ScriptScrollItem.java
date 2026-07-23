package io.github.khayashi4337.micradrone.drone;

import java.util.List;

import io.github.khayashi4337.micradrone.MicraDroneClient;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.WritableBookItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/**
 * A portable, freely-rewritable carrier for a script (GitHub issue #1): unlike the {@code .mdrone}
 * files under a controller's script folder, this can be handed or traded to another player.
 * Subclasses {@link WritableBookItem} purely to reuse its book-and-quill edit screen for free - its
 * pages never "sign"/lock into a {@code WrittenBookItem}, so it stays rewritable forever, which is
 * exactly the behavior the issue asked for.
 * <p>
 * Block-facing behavior lives in two verified-dispatch-order places (see the issue-#1 saga for why
 * this matters): slotting into a controller is {@link DroneControllerBlock#useItemOn} (an
 * {@code Item#useOn} override here would never be reached), and the enchanting-table inscription
 * below uses {@code onItemUseFirst}, NeoForge's item hook that runs BEFORE any block interaction -
 * the only way to act before the table's own {@code useWithoutItem} opens the vanilla enchanting
 * screen. Used anywhere else (including empty air), this item behaves as a plain
 * {@code WritableBookItem}: right-clicking opens the book-and-quill edit screen.
 */
public class ScriptScrollItem extends WritableBookItem {
    public ScriptScrollItem(Properties properties) {
        super(properties);
    }

    /**
     * A BLANK scroll used on an enchanting table opens the sample picker (issue #8) instead of the
     * vanilla enchanting screen; a written scroll passes through, so the table keeps working
     * normally with it in hand. Runs on both sides: the client opens the screen, the server just
     * consumes the click (returning PASS server-side would open the vanilla enchanting menu on top).
     */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Level level = context.getLevel();
        if (!level.getBlockState(context.getClickedPos()).is(Blocks.ENCHANTING_TABLE)
                || ScriptChestLibrary.scrollSource(stack).isPresent()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            MicraDroneClient.openEnchantScrollScreen(context.getClickedPos(), context.getHand());
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.micradrone.script_scroll.tooltip"));
    }
}
