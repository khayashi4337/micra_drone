package io.github.khayashi4337.micradrone.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import io.github.khayashi4337.micradrone.MicraDroneClient;
import io.github.khayashi4337.micradrone.drone.net.FillScrollPayload;
import io.github.khayashi4337.micradrone.drone.net.RequestLogPayload;
import io.github.khayashi4337.micradrone.drone.net.RunScriptPayload;
import io.github.khayashi4337.micradrone.drone.net.RunScrollPayload;
import io.github.khayashi4337.micradrone.drone.net.SetAliasPayload;
import io.github.khayashi4337.micradrone.drone.net.StopScriptPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Opened by right-clicking a Drone Controller: alias, points-per-crop, script picker, log, Run/Stop.
 * The unlock shop lives on the paired Corner Marker instead (see {@code ShopScreen}) - an earlier
 * tab-based version of this screen packed both into one window, but the two tab buttons didn't read
 * as real tabs and, worse, had a real layout bug (points text overlapping the alias box). Splitting
 * across two single-purpose screens sidesteps both problems without needing vanilla's full
 * TabNavigationBar/TabManager system, which is built for full-screen settings-style screens, not a
 * small popup like this one.
 * Client-only, so no logic here is unit-testable - see MicraDroneClient's note on manual verification.
 */
public class DroneScreen extends Screen {
    private static final int LOG_WIDTH = 240;
    private static final int LOG_HEIGHT = 110;
    private static final int SCRIPT_LIST_HEIGHT = 64;
    private static final int DESCRIPTION_HEIGHT = 28;
    private static final String DEFAULT_SCRIPT_NAME = "main.mdrone";
    private static final int ALIAS_ROW_Y = 4;
    private static final int ALIAS_ROW_HEIGHT = 18;
    // Alias row occupies [4, 22). Points lines start at 24 (2px gap) and are 9px tall each; with up
    // to 2 crops shown that's [24, 42). Script list starts at 46, a further 4px clear of that; the
    // description box for whichever script is selected sits right below it.
    private static final int POINTS_LINES_Y = 24;
    private static final int SCRIPT_LIST_Y = 46;
    private static final int DESCRIPTION_Y = SCRIPT_LIST_Y + SCRIPT_LIST_HEIGHT + 6;

    private final BlockPos pos;

    private MultiLineEditBox logBox;
    private MultiLineEditBox descriptionBox;
    private EditBox aliasBox;
    private ScriptListWidget scriptList;
    private List<Component> pointsLines = List.of();

    public DroneScreen(BlockPos pos) {
        super(Component.translatable("gui.micradrone.drone_screen.title"));
        this.pos = pos;
    }

    @Override
    protected void init() {
        int left = (this.width - LOG_WIDTH) / 2;

        aliasBox = new EditBox(this.font, left, ALIAS_ROW_Y, LOG_WIDTH - 76 - 4, ALIAS_ROW_HEIGHT,
                Component.translatable("gui.micradrone.drone_screen.alias"));
        aliasBox.setMaxLength(48);
        addRenderableWidget(aliasBox);
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.drone_screen.set_alias"),
                b -> PacketDistributor.sendToServer(new SetAliasPayload(pos, aliasBox.getValue())))
                .bounds(left + LOG_WIDTH - 76, ALIAS_ROW_Y, 76, ALIAS_ROW_HEIGHT)
                .build());

        // Created before scriptList so its overridden setSelected (triggered by replaceEntries below,
        // even for this very first population) always has somewhere to write the description to.
        // MultiLineEditBox has no read-only mode (same as logBox below) - it's technically editable,
        // but nothing ever reads player-typed text back out of it, so it's harmless as a display-only box.
        descriptionBox = new MultiLineEditBox(this.font, left, DESCRIPTION_Y, LOG_WIDTH, DESCRIPTION_HEIGHT,
                Component.translatable("gui.micradrone.drone_screen.script_description_placeholder"),
                Component.translatable("gui.micradrone.drone_screen.script_description"));
        addRenderableWidget(descriptionBox);

        scriptList = new ScriptListWidget(Minecraft.getInstance(), LOG_WIDTH, SCRIPT_LIST_HEIGHT, SCRIPT_LIST_Y, 16);
        scriptList.setX(left);
        scriptList.replaceEntries(Map.of(DEFAULT_SCRIPT_NAME, DEFAULT_SCRIPT_NAME));
        addRenderableWidget(scriptList);

        int top = DESCRIPTION_Y + DESCRIPTION_HEIGHT + 6;
        logBox = new MultiLineEditBox(this.font, left, top, LOG_WIDTH, LOG_HEIGHT,
                Component.translatable("gui.micradrone.drone_screen.log_placeholder"),
                Component.translatable("gui.micradrone.drone_screen.log"));
        addRenderableWidget(logBox);

        int buttonY = top + LOG_HEIGHT + 8;
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.drone_screen.run"),
                b -> PacketDistributor.sendToServer(new RunScriptPayload(pos, scriptList.selectedFileName())))
                .bounds(left, buttonY, 80, 20)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.drone_screen.stop"),
                b -> PacketDistributor.sendToServer(new StopScriptPayload(pos)))
                .bounds(left + LOG_WIDTH - 80, buttonY, 80, 20)
                .build());

        int scriptsFolderY = buttonY + 24;
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.drone_screen.open_scripts_folder"),
                b -> MicraDroneClient.openScriptsFolder())
                .bounds(left, scriptsFolderY, LOG_WIDTH, 20)
                .build());

        int helpY = scriptsFolderY + 24;
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.drone_screen.help"),
                b -> MicraDroneClient.openHelpFolder())
                .bounds(left, helpY, LOG_WIDTH, 20)
                .build());

        // Scroll import/export (GitHub issue #1): explicit buttons instead of overloading the
        // controller's own right-click with hidden modifiers (sneak, held-item checks) - that design
        // broke twice against vanilla's Item/Block interaction dispatch order in real-machine testing.
        // Both act on whatever ScriptScrollItem is in the player's main hand; the server reports why
        // nothing happened (not holding a scroll, scroll is blank) via chat if the action can't apply.
        int scrollRowY = helpY + 24;
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.drone_screen.copy_to_scroll"),
                b -> PacketDistributor.sendToServer(new FillScrollPayload(pos, scriptList.selectedFileName())))
                .bounds(left, scrollRowY, (LOG_WIDTH - 4) / 2, 20)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.drone_screen.run_scroll"),
                b -> PacketDistributor.sendToServer(new RunScrollPayload(pos)))
                .bounds(left + (LOG_WIDTH - 4) / 2 + 4, scrollRowY, (LOG_WIDTH - 4) / 2, 20)
                .build());

        PacketDistributor.sendToServer(new RequestLogPayload(pos));
    }

    private static String displayName(String cropName) {
        return cropName.isEmpty() ? cropName
                : Character.toUpperCase(cropName.charAt(0)) + cropName.substring(1).toLowerCase(Locale.ROOT);
    }

    /** Called from {@code MicraDroneClient} when a DroneLogPayload arrives for this controller. */
    public void updateLog(BlockPos sourcePos, List<String> lines, Map<String, Long> pointsByCrop,
            Map<String, String> scriptDescriptions, String selectedScript, String alias) {
        if (!sourcePos.equals(this.pos)) {
            return;
        }
        logBox.setValue(String.join("\n", lines));

        List<Component> newLines = new ArrayList<>();
        for (Map.Entry<String, Long> entry : new TreeMap<>(pointsByCrop).entrySet()) {
            newLines.add(Component.literal(displayName(entry.getKey()) + ": " + entry.getValue()));
        }
        pointsLines = newLines;

        if (!scriptDescriptions.isEmpty()) {
            scriptList.replaceEntries(scriptDescriptions);
            scriptList.selectFileName(selectedScript);
        }

        // Don't clobber text the player is actively typing with a stale server echo.
        if (!aliasBox.isFocused()) {
            aliasBox.setValue(alias);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        // Between the alias row's bottom (ALIAS_ROW_Y + ALIAS_ROW_HEIGHT) and the script list's top
        // (SCRIPT_LIST_Y) there's a fixed 18px gap; up to 2 point lines (9px each) fit without
        // overlapping either neighbor.
        int y = ALIAS_ROW_Y + ALIAS_ROW_HEIGHT + 1;
        for (Component line : pointsLines) {
            guiGraphics.drawCenteredString(this.font, line, this.width / 2, y, 0xFFFFFF);
            y += 9;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Scrollable list of this controller's available scripts. Each row shows the raw file name -
     * the description (see ScriptFileStore#describeScript) is shown separately, in descriptionBox,
     * for whichever entry is currently selected (see the overridden setSelected below).
     */
    private final class ScriptListWidget extends ObjectSelectionList<ScriptListWidget.ScriptEntry> {
        ScriptListWidget(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        void replaceEntries(Map<String, String> descriptions) {
            String previouslySelected = selectedFileName();
            clearEntries();
            new TreeMap<>(descriptions).forEach((fileName, description) -> addEntry(new ScriptEntry(fileName, description)));
            selectFileName(previouslySelected);
            if (getSelected() == null && getItemCount() > 0) {
                setSelected(getEntry(0));
            }
        }

        void selectFileName(String fileName) {
            for (int i = 0; i < getItemCount(); i++) {
                ScriptEntry entry = getEntry(i);
                if (entry.fileName.equals(fileName)) {
                    setSelected(entry);
                    return;
                }
            }
        }

        String selectedFileName() {
            ScriptEntry selected = getSelected();
            return selected != null ? selected.fileName : DEFAULT_SCRIPT_NAME;
        }

        /** Single hook point for every way the selection can change: clicks, arrow keys, and the programmatic calls above. */
        @Override
        public void setSelected(ScriptEntry selected) {
            super.setSelected(selected);
            descriptionBox.setValue(selected != null ? selected.description : "");
        }

        @Override
        public int getRowWidth() {
            return this.width - 10;
        }

        final class ScriptEntry extends ObjectSelectionList.Entry<ScriptEntry> {
            private final String fileName;
            private final String description;
            private final Component fileNameLabel;

            ScriptEntry(String fileName, String description) {
                this.fileName = fileName;
                this.description = description;
                this.fileNameLabel = Component.literal(fileName);
            }

            @Override
            public Component getNarration() {
                return Component.literal(fileName + ": " + description);
            }

            @Override
            public void render(GuiGraphics guiGraphics, int index, int top, int left, int width, int height,
                    int mouseX, int mouseY, boolean hovering, float partialTick) {
                guiGraphics.drawString(DroneScreen.this.font, fileNameLabel, left + 2, top + (height - 8) / 2, 0xFFFFFF);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                ScriptListWidget.this.setSelected(this);
                return true;
            }
        }
    }
}
