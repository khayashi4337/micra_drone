package io.github.khayashi4337.micradrone.client;

import io.github.khayashi4337.micradrone.drone.SampleCatalog;
import io.github.khayashi4337.micradrone.drone.ScriptFileStore;
import io.github.khayashi4337.micradrone.drone.ScriptScrollItem;
import io.github.khayashi4337.micradrone.drone.ScrollEnchanter;
import io.github.khayashi4337.micradrone.drone.net.EnchantScrollPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The enchanting table's scroll picker (issue #8, extended by the GUI-reduction follow-up):
 * dropping a BLANK script scroll into the vanilla enchanting table's own item slot - the normal
 * drag-and-drop way that GUI already works - opens this screen in place of it (see
 * {@code EnchantTableWatcher}). Lists every {@link SampleCatalog} entry (ones needing more
 * bookshelves than currently surround the table are greyed out with their requirement shown, so
 * building up the library is a visible goal - the bookshelf count re-runs vanilla's own rule
 * against the synced client level every tick, so placing bookshelves while the screen is open
 * unlocks entries live), followed by every already-written scroll sitting in ANY container (chest,
 * barrel, shulker box, chiseled bookshelf, ...) at one of those same 16 positions
 * ({@link ScrollEnchanter#findCopySources}, a flat-cost copy with no lock - computed once at open
 * time, not re-scanned every tick like the sample lock does, since rearranging a table's
 * bookshelf-position contents mid-picker is not a case worth the extra complexity). A plain chest
 * works fine here and gives the familiar chest screen instead of the vanilla chiseled bookshelf's
 * fiddly per-slot click interaction (a real-machine try found the latter not great).
 * <p>
 * Extends {@link AbstractContainerScreen} over the SAME {@link EnchantmentMenu} the vanilla
 * enchanting screen would have used, instead of a bare {@code Screen} - a real-machine report
 * found that a bare {@code Screen} (no slot rendering at all) made it impossible to place lapis
 * lazuli once this picker was showing, since there was no slot UI left to drop it into. Reusing
 * the real menu means the item slot, lapis slot, and player inventory grid all render and accept
 * drag-and-drop exactly like any other vanilla container screen (verified prior art: the
 * "Enchancement" mod, github.com/MoriyaShiine/enchancement, replaces the enchanting table's
 * picker the same way - a custom list layered over the real container slots - rather than
 * reinventing slot interaction). The picker's own list/description/buttons occupy a separate panel
 * to the right of the vanilla slot area so neither overlaps the other.
 * <p>
 * Inscribing sends {@link EnchantScrollPayload}; the server re-validates everything, takes the
 * lapis from the table's own lapis slot, and writes straight into the scroll still sitting in the
 * item slot (see {@code ScrollEnchanter}). Since this screen stands in for the vanilla
 * {@code EnchantmentScreen} over the same menu, the inherited default {@code onClose()} (close the
 * container, return items) is exactly correct - no override needed here. Client-only, so no logic
 * here is unit-testable - verified manually in-game.
 */
public class EnchantScrollScreen extends AbstractContainerScreen<EnchantmentMenu> {
    /** {@link EnchantmentMenu} slot indices - see the vanilla source (slot 0 = item, slot 1 = lapis). */
    private static final int ITEM_SLOT = 0;
    private static final int LAPIS_SLOT = 1;

    /** Matches the vanilla enchanting slot layout ({@code EnchantmentMenu}'s own slot coordinates). */
    private static final int CONTAINER_WIDTH = 176;
    private static final int CONTAINER_HEIGHT = 166;
    private static final int GAP = 14;

    private static final int PANEL_WIDTH = 240;
    private static final int HEADING_Y = 8;
    private static final int BOOKSHELVES_Y = 20;
    private static final int LIST_Y = 32;
    private static final int LIST_HEIGHT = 112;
    private static final int DESCRIPTION_Y = LIST_Y + LIST_HEIGHT + 6;
    private static final int DESCRIPTION_HEIGHT = 28;
    private static final int BUTTON_Y = DESCRIPTION_Y + DESCRIPTION_HEIGHT + 8;
    private static final int PANEL_HEIGHT = BUTTON_Y + 20 + 8;

    private final BlockPos tablePos;

    private MultiLineEditBox descriptionBox;
    private PickerListWidget pickerList;
    private Button inscribeButton;
    private int bookshelfCount;
    private int lapisCount;

    public EnchantScrollScreen(BlockPos tablePos, EnchantmentMenu menu, Inventory playerInventory) {
        super(menu, playerInventory, Component.translatable("gui.micradrone.enchant_scroll.title"));
        this.tablePos = tablePos;
        this.imageWidth = CONTAINER_WIDTH + GAP + PANEL_WIDTH;
        this.imageHeight = PANEL_HEIGHT;
    }

    @Override
    protected void init() {
        super.init();
        int panelLeft = leftPos + CONTAINER_WIDTH + GAP;
        recountBookshelves();
        recountLapis();

        descriptionBox = new MultiLineEditBox(this.font, panelLeft, topPos + DESCRIPTION_Y, PANEL_WIDTH, DESCRIPTION_HEIGHT,
                Component.translatable("gui.micradrone.drone_screen.script_description_placeholder"),
                Component.translatable("gui.micradrone.drone_screen.script_description"));
        addRenderableWidget(descriptionBox);

        pickerList = new PickerListWidget(Minecraft.getInstance(), PANEL_WIDTH, LIST_HEIGHT, topPos + LIST_Y, 16);
        pickerList.setX(panelLeft);
        for (int i = 0; i < SampleCatalog.ALL.size(); i++) {
            pickerList.addSampleRow(i);
        }
        if (this.minecraft != null && this.minecraft.level != null) {
            for (ScrollEnchanter.CopySource copy : ScrollEnchanter.findCopySources(this.minecraft.level, tablePos)) {
                pickerList.addCopyRow(copy);
            }
        }
        addRenderableWidget(pickerList);

        int halfW = (PANEL_WIDTH - 4) / 2;
        inscribeButton = addRenderableWidget(Button.builder(
                Component.translatable("gui.micradrone.enchant_scroll.inscribe"), b -> inscribe())
                .bounds(panelLeft, topPos + BUTTON_Y, halfW, 20)
                .build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.micradrone.enchant_scroll.cancel"), b -> onClose())
                .bounds(panelLeft + halfW + 4, topPos + BUTTON_Y, halfW, 20)
                .build());

        pickerList.selectFirst();
        refreshInscribeButton();
    }

    private void inscribe() {
        PickerListWidget.Row selected = pickerList.getSelected();
        if (selected == null || selected.locked()) {
            return;
        }
        if (selected.copy != null) {
            PacketDistributor.sendToServer(
                    new EnchantScrollPayload(tablePos, -1, selected.copy.bookshelfOffsetIndex(), selected.copy.slot()));
        } else {
            PacketDistributor.sendToServer(new EnchantScrollPayload(tablePos, selected.sampleIndex, -1, -1));
        }
        onClose();
    }

    private void recountBookshelves() {
        if (this.minecraft != null && this.minecraft.level != null) {
            bookshelfCount = ScrollEnchanter.countBookshelves(this.minecraft.level, tablePos);
        }
    }

    private void recountLapis() {
        lapisCount = this.menu.getSlot(LAPIS_SLOT).getItem().getCount();
    }

    private void refreshInscribeButton() {
        PickerListWidget.Row selected = pickerList != null ? pickerList.getSelected() : null;
        if (inscribeButton != null) {
            inscribeButton.active = selected != null && !selected.locked();
        }
    }

    /**
     * Real-machine request: once the item slot no longer holds a scroll, OR the lapis slot runs dry,
     * hand control back to vanilla's own enchanting screen over the SAME menu - mirrors the open
     * condition in {@code EnchantTableWatcher} (blank scroll AND lapis both present), just in
     * reverse. The player might still want to enchant a normal item, or place scroll/lapis again
     * later ({@code EnchantTableWatcher} stays armed for exactly that).
     */
    @Override
    protected void containerTick() {
        super.containerTick();
        boolean hasScroll = this.menu.getSlot(ITEM_SLOT).getItem().getItem() instanceof ScriptScrollItem;
        boolean hasLapis = !this.menu.getSlot(LAPIS_SLOT).getItem().isEmpty();
        if (!hasScroll || !hasLapis) {
            if (this.minecraft != null && this.minecraft.player != null) {
                this.minecraft.setScreen(new EnchantmentScreen(this.menu, this.minecraft.player.getInventory(),
                        Component.translatable("container.enchant")));
            }
            return;
        }
        recountBookshelves();
        recountLapis();
        refreshInscribeButton();
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        int panelLeft = leftPos + CONTAINER_WIDTH + GAP;
        guiGraphics.fill(leftPos - 4, topPos - 4, leftPos + CONTAINER_WIDTH + 4, topPos + CONTAINER_HEIGHT + 4, 0xC0101010);
        guiGraphics.fill(panelLeft - 4, topPos - 4, panelLeft + PANEL_WIDTH + 4, topPos + PANEL_HEIGHT + 4, 0xC0101010);
        for (Slot slot : this.menu.slots) {
            guiGraphics.fill(leftPos + slot.x - 1, topPos + slot.y - 1, leftPos + slot.x + 17, topPos + slot.y + 17, 0xFF8B8B8B);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
        int panelLeft = leftPos + CONTAINER_WIDTH + GAP;
        guiGraphics.drawCenteredString(this.font, this.title, panelLeft + PANEL_WIDTH / 2, topPos + HEADING_Y, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font,
                Component.translatable("gui.micradrone.enchant_scroll.bookshelves",
                        bookshelfCount, SampleCatalog.MAX_BOOKSHELVES),
                panelLeft + PANEL_WIDTH / 2, topPos + BOOKSHELVES_Y, 0xFFFFFF);
    }

    /**
     * One row per entry: {@link SampleCatalog} samples first (locked by bookshelf count, name +
     * cost/requirement), then bookshelf copy candidates (flat {@link ScrollEnchanter#COPY_LAPIS_COST}).
     * Either kind is also locked whenever its lapis cost exceeds what's currently sitting in the
     * table's lapis slot - real-machine request: with only 1 lapis placed, entries costing 2+ show
     * greyed out and can't be selected, instead of failing only after Inscribe is pressed.
     */
    private final class PickerListWidget extends ObjectSelectionList<PickerListWidget.Row> {
        PickerListWidget(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        void addSampleRow(int sampleIndex) {
            addEntry(new Row(sampleIndex, null));
        }

        void addCopyRow(ScrollEnchanter.CopySource copy) {
            addEntry(new Row(-1, copy));
        }

        void selectFirst() {
            if (getItemCount() > 0) {
                setSelected(getEntry(0));
            }
        }

        /** Single hook point for every way the selection can change (same pattern as DroneScreen). */
        @Override
        public void setSelected(Row selected) {
            super.setSelected(selected);
            descriptionBox.setValue(selected != null ? selected.description() : "");
            refreshInscribeButton();
        }

        @Override
        public int getRowWidth() {
            return this.width - 10;
        }

        final class Row extends ObjectSelectionList.Entry<Row> {
            final int sampleIndex;
            final ScrollEnchanter.CopySource copy;

            Row(int sampleIndex, ScrollEnchanter.CopySource copy) {
                this.sampleIndex = sampleIndex;
                this.copy = copy;
            }

            int cost() {
                return copy != null ? ScrollEnchanter.COPY_LAPIS_COST : SampleCatalog.ALL.get(sampleIndex).lapisCost();
            }

            boolean bookshelfLocked() {
                return copy == null && !SampleCatalog.isUnlocked(sampleIndex, EnchantScrollScreen.this.bookshelfCount);
            }

            boolean lapisLocked() {
                return cost() > EnchantScrollScreen.this.lapisCount;
            }

            boolean locked() {
                return bookshelfLocked() || lapisLocked();
            }

            String displayName() {
                return copy != null ? copy.displayName() : SampleCatalog.ALL.get(sampleIndex).displayName();
            }

            String description() {
                return copy != null ? copy.description()
                        : ScriptFileStore.describeScript(SampleCatalog.ALL.get(sampleIndex).source(), displayName());
            }

            @Override
            public Component getNarration() {
                return Component.literal(displayName() + ": " + status().getString());
            }

            private Component status() {
                if (bookshelfLocked()) {
                    SampleCatalog.Sample sample = SampleCatalog.ALL.get(sampleIndex);
                    return Component.translatable("gui.micradrone.enchant_scroll.locked", sample.requiredBookshelves());
                }
                if (lapisLocked()) {
                    return Component.translatable("gui.micradrone.enchant_scroll.needs_lapis", cost());
                }
                return Component.translatable("gui.micradrone.enchant_scroll.cost", cost());
            }

            @Override
            public void render(GuiGraphics guiGraphics, int rowIndex, int top, int left, int width, int height,
                    int mouseX, int mouseY, boolean hovering, float partialTick) {
                boolean unlocked = !locked();
                int textY = top + (height - 8) / 2;
                guiGraphics.drawString(EnchantScrollScreen.this.font, displayName(),
                        left + 2, textY, unlocked ? 0xFFFFFF : 0x808080);
                Component status = status();
                int statusWidth = EnchantScrollScreen.this.font.width(status);
                guiGraphics.drawString(EnchantScrollScreen.this.font, status,
                        left + width - statusWidth - 6, textY, unlocked ? 0x8090FF : 0x808080);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                PickerListWidget.this.setSelected(this);
                return true;
            }
        }
    }
}
