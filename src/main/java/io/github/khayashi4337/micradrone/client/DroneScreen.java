package io.github.khayashi4337.micradrone.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import io.github.khayashi4337.micradrone.MicraDroneClient;
import io.github.khayashi4337.micradrone.drone.net.RequestLogPayload;
import io.github.khayashi4337.micradrone.drone.net.RunScriptPayload;
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
 * Opened by right-clicking a Drone Controller. Run/Stop send commands to the server; the log box
 * shows whatever {@code DroneLogPayload} snapshot the server last pushed for this controller.
 * Client-only, so no logic here is unit-testable - see MicraDroneClient's note on manual verification.
 */
public class DroneScreen extends Screen {
    private static final int LOG_WIDTH = 240;
    private static final int LOG_HEIGHT = 110;
    private static final int SCRIPT_LIST_HEIGHT = 64;
    private static final String DEFAULT_SCRIPT_NAME = "main.mdrone";

    private final BlockPos pos;
    private MultiLineEditBox logBox;
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

        aliasBox = new EditBox(this.font, left, 4, LOG_WIDTH - 76 - 4, 18,
                Component.translatable("gui.micradrone.drone_screen.alias"));
        aliasBox.setMaxLength(48);
        addRenderableWidget(aliasBox);
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.drone_screen.set_alias"),
                b -> PacketDistributor.sendToServer(new SetAliasPayload(pos, aliasBox.getValue())))
                .bounds(left + LOG_WIDTH - 76, 4, 76, 18)
                .build());

        int scriptListY = 48;
        scriptList = new ScriptListWidget(Minecraft.getInstance(), LOG_WIDTH, SCRIPT_LIST_HEIGHT, scriptListY, 16);
        scriptList.setX(left);
        scriptList.replaceEntries(Map.of(DEFAULT_SCRIPT_NAME, DEFAULT_SCRIPT_NAME));
        addRenderableWidget(scriptList);

        int top = scriptListY + SCRIPT_LIST_HEIGHT + 6;
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

        PacketDistributor.sendToServer(new RequestLogPayload(pos));
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
            String cropName = entry.getKey();
            String label = cropName.isEmpty() ? cropName
                    : Character.toUpperCase(cropName.charAt(0)) + cropName.substring(1).toLowerCase(Locale.ROOT);
            newLines.add(Component.literal(label + ": " + entry.getValue()));
        }
        pointsLines = newLines;

        if (!scriptDescriptions.isEmpty()) {
            scriptList.replaceEntries(scriptDescriptions);
            scriptList.selectFileName(selectedScript);
        }

        // Don't clobber text the player is actively typing with a stale server echo.
        if (aliasBox != null && !aliasBox.isFocused()) {
            aliasBox.setValue(alias);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        int y = 26;
        for (Component line : pointsLines) {
            guiGraphics.drawCenteredString(this.font, line, this.width / 2, y, 0xFFFFFF);
            y += 10;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Scrollable list of this controller's available scripts, showing each one's description (see ScriptFileStore#describeScript). */
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

        @Override
        public int getRowWidth() {
            return this.width - 10;
        }

        final class ScriptEntry extends ObjectSelectionList.Entry<ScriptEntry> {
            private final String fileName;
            private final Component label;

            ScriptEntry(String fileName, String description) {
                this.fileName = fileName;
                this.label = Component.literal(description);
            }

            @Override
            public Component getNarration() {
                return label;
            }

            @Override
            public void render(GuiGraphics guiGraphics, int index, int top, int left, int width, int height,
                    int mouseX, int mouseY, boolean hovering, float partialTick) {
                guiGraphics.drawString(DroneScreen.this.font, label, left + 2, top + (height - 8) / 2, 0xFFFFFF);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                ScriptListWidget.this.setSelected(this);
                return true;
            }
        }
    }
}
