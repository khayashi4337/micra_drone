package io.github.khayashi4337.micradrone.drone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EnchantingTableBlock;

/**
 * Enchanting-table scroll inscription (issue #8): dropping a blank script scroll into the
 * vanilla enchanting table's own item slot (the normal, drag-and-drop way players already use
 * that GUI - see the real-machine finding that a hold-then-click design was the wrong mental
 * model) opens a picker of {@link SampleCatalog} entries; the pick lands here (via
 * EnchantScrollPayload) to be re-validated and written server-side, directly into the scroll
 * still sitting in the table's slot. Bookshelves are counted with the exact vanilla rule
 * ({@link EnchantingTableBlock#isValidBookShelf} over
 * {@link EnchantingTableBlock#BOOKSHELF_OFFSETS}), so surrounding the table with books unlocks
 * deeper knowledge, and each inscription costs lapis like a vanilla enchant (free in creative).
 * Failures are reported to chat; a silent no-op is never acceptable here (see the issue-#1 saga).
 */
public final class ScrollEnchanter {
    /** Extra reach slack on top of the player's block-interaction range, vanilla's usual allowance. */
    private static final double INTERACT_DISTANCE_SLACK = 4.0;
    /** {@link EnchantmentMenu}'s item slot index (slot 1 is the lapis slot) - see the vanilla source. */
    private static final int ITEM_SLOT = 0;

    private ScrollEnchanter() {
    }

    /** Valid bookshelves around the table, 0..15 - runs against either side's Level (blocks are synced). */
    public static int countBookshelves(Level level, BlockPos tablePos) {
        int count = 0;
        for (BlockPos offset : EnchantingTableBlock.BOOKSHELF_OFFSETS) {
            if (EnchantingTableBlock.isValidBookShelf(level, tablePos, offset)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Validates the whole request against server state, then inscribes the blank scroll sitting in
     * the player's currently-open {@link EnchantmentMenu} item slot - the scroll never leaves that
     * slot; closing the menu afterward (client-driven) hands it back via vanilla's own
     * {@code clearContainer} container-close behavior, same as picking up any other enchant-table item.
     */
    public static void enchant(ServerPlayer player, BlockPos tablePos, int sampleIndex) {
        ServerLevel level = player.serverLevel();
        if (!level.getBlockState(tablePos).is(Blocks.ENCHANTING_TABLE)
                || !player.canInteractWithBlock(tablePos, INTERACT_DISTANCE_SLACK)) {
            player.sendSystemMessage(Component.literal("[scroll] the enchanting table is out of reach"));
            return;
        }
        if (!(player.containerMenu instanceof EnchantmentMenu)) {
            player.sendSystemMessage(Component.literal("[scroll] open the enchanting table and put a blank scroll in it first"));
            return;
        }
        Slot itemSlot = player.containerMenu.getSlot(ITEM_SLOT);
        ItemStack stack = itemSlot.getItem();
        if (!ScriptScrollItem.isBlank(stack)) {
            player.sendSystemMessage(Component.literal("[scroll] put a blank script scroll in the enchanting table's item slot"));
            return;
        }
        if (!SampleCatalog.isUnlocked(sampleIndex, countBookshelves(level, tablePos))) {
            player.sendSystemMessage(Component.literal("[scroll] that knowledge needs more bookshelves around the table"));
            return;
        }
        SampleCatalog.Sample sample = SampleCatalog.ALL.get(sampleIndex);
        if (!player.getAbilities().instabuild && !consumeLapis(player.getInventory(), sample.lapisCost())) {
            player.sendSystemMessage(Component.literal("[scroll] inscribing '" + sample.displayName()
                    + "' costs " + sample.lapisCost() + " lapis lazuli"));
            return;
        }
        ScriptChestLibrary.writeScrollSource(stack, sample.source());
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(sample.displayName()));
        itemSlot.setChanged();
        player.containerMenu.broadcastChanges();
        level.playSound(null, tablePos, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS,
                1.0F, level.random.nextFloat() * 0.1F + 0.9F);
        level.sendParticles(ParticleTypes.ENCHANT,
                tablePos.getX() + 0.5, tablePos.getY() + 1.2, tablePos.getZ() + 0.5, 32, 0.3, 0.4, 0.3, 0.0);
        player.sendSystemMessage(Component.literal("[scroll] inscribed '" + sample.displayName() + "'"));
    }

    /** Takes {@code cost} lapis from {@code inventory}; false (and takes nothing) if there isn't enough. */
    private static boolean consumeLapis(Inventory inventory, int cost) {
        int have = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(Items.LAPIS_LAZULI)) {
                have += stack.getCount();
            }
        }
        if (have < cost) {
            return false;
        }
        int remaining = cost;
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(Items.LAPIS_LAZULI)) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
        }
        inventory.setChanged();
        return true;
    }
}
