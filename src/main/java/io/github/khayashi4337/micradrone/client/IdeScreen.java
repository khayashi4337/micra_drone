package io.github.khayashi4337.micradrone.client;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.github.khayashi4337.micradrone.drone.CornerMarkerScan;
import io.github.khayashi4337.micradrone.drone.DroneControllerBlockEntity;
import io.github.khayashi4337.micradrone.drone.net.DebugCommandPayload;
import io.github.khayashi4337.micradrone.drone.net.DebugStatePayload;
import io.github.khayashi4337.micradrone.drone.net.RequestLogPayload;
import io.github.khayashi4337.micradrone.drone.net.RequestScriptSourcePayload;
import io.github.khayashi4337.micradrone.drone.net.RunScriptPayload;
import io.github.khayashi4337.micradrone.drone.net.SaveScriptPayload;
import io.github.khayashi4337.micradrone.drone.net.ScriptEntry;
import io.github.khayashi4337.micradrone.drone.net.SelectScriptPayload;
import io.github.khayashi4337.micradrone.drone.net.SetBreakpointsPayload;
import io.github.khayashi4337.micradrone.drone.net.StopScriptPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Fullscreen script IDE (issue #6) - the controller's ONLY screen (GUI-reduction follow-up
 * dissolved the separate list/log screen, {@code DroneScreen}, into this one's right half as a
 * toggleable "list mode" - see {@link #listMode}). Left half is a {@link MultiLineEditBox} editor
 * for one of the controller's scripts; the right half is normally the real field seen from
 * straight above (the game's own camera hovers over the plot, {@link IdeCameraController}, 林さん's
 * "float the viewpoint" idea) - pressing List swaps it for the script picker (script list +
 * description + log + points, exactly what {@code DroneScreen} used to show on its own) instead,
 * since hiding the live view briefly to pick a file doesn't hurt the experience (林さん's call).
 * Picking a script there sends {@link SelectScriptPayload}, switches the editor to it, and closes
 * list mode automatically; pressing List again with nothing picked just closes it.
 *
 * <p>Selection IS the run target now: the jukebox-style item slot that used to decide what a
 * redstone signal runs is gone (GUI reduction follow-up) - a lever now runs whichever script was
 * selected last. Right-clicking the controller with an empty hand opens this screen without yet
 * knowing which script that is (there's no fixed slot id to assume anymore); {@link #updateLog}
 * resolves it from the server's answer the first time one arrives (see {@link #scriptId}'s doc).
 *
 * <p>Debugger (issue #6): the editor gutter shows line numbers - click one to toggle a
 * breakpoint (red). The line about to execute is highlighted yellow, live. Pause/Resume, Step,
 * and Step Out (of the current loop) drive the server-side {@code DebugController} through
 * {@link DebugCommandPayload}; state comes back via {@link DebugStatePayload}. The highlight maps
 * lines of the SAVED script, so debugging starts from Save &amp; Run - unsaved edits shift lines.
 *
 * <p>Client-only, so no logic here is unit-testable (the camera math and debugger core are - see
 * IdeCameraMathTest/DebugControllerTest); the screen is verified manually in-game.
 */
public class IdeScreen extends Screen {
    private static final int MARGIN = 8;
    private static final int TOP_Y = 24;
    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW_GAP = 4;
    /** Width of the line-number/breakpoint gutter to the left of the editor. */
    private static final int GUTTER_WIDTH = 20;
    /** How often (client ticks) the plot size/direction is re-resolved from the blocks. */
    private static final int RESCAN_INTERVAL_TICKS = 20;
    // List-mode panel (right half) sub-region heights, top to bottom: points text, script list,
    // description, then log fills whatever's left.
    private static final int POINTS_HEIGHT = 20;
    private static final int LIST_HEIGHT = 90;
    private static final int DESCRIPTION_HEIGHT = 28;

    private final BlockPos pos;
    private final IdeCameraController cameraController;

    /**
     * Empty means "not resolved yet" - right-clicking the controller with an empty hand no longer
     * has a fixed id to open on (the jukebox slot is gone), so this starts blank and
     * {@link #updateLog} fills it in from the server's {@code selectedScript} the first time a
     * {@code DroneLogPayload} arrives. Picking an entry in list mode resolves it immediately
     * instead, since the entry's own id/name are already known at click time.
     */
    private String scriptId;
    /** Human-facing name for the heading; mutable for the same reason as {@link #scriptId}. */
    private String displayName;

    private DebugEditBox editor;
    private Button pauseResumeButton;
    private Button listButton;

    // Survive init() re-runs on window resize/list-mode toggles: widgets are rebuilt, this isn't.
    private String editorText = "";
    private boolean sourceRequested = false;
    private boolean logRequested = false;

    // Debugger state, driven by DebugStatePayload; breakpoints are the client's working copy.
    private final Set<Integer> breakpoints = new HashSet<>();
    private int debugState = DebugStatePayload.STATE_IDLE;

    // Gutter geometry, computed in init() and reused by render()/mouseClicked().
    private int editorTop;
    private int editorHeight;

    // List-mode state, refreshed from every DroneLogPayload regardless of whether list mode is
    // currently showing, so it's ready the instant the player opens it.
    private boolean listMode = false;
    private List<ScriptEntry> availableScripts = List.of();
    private String selectedScriptFromServer = "";
    private List<String> logLines = List.of();
    private Map<String, Long> pointsByCrop = Map.of();
    private ScriptListWidget scriptList;
    private MultiLineEditBox descriptionBox;
    private MultiLineEditBox logBox;

    private CornerMarkerScan.PlotBounds bounds = new CornerMarkerScan.PlotBounds(
            DroneControllerBlockEntity.DEFAULT_WORLD_SIZE, 1, 1, false, 0);
    private int tickCounter = 0;

    public IdeScreen(BlockPos pos, String scriptId, String displayName) {
        super(Component.translatable("gui.micradrone.ide_screen.title"));
        this.pos = pos;
        this.scriptId = scriptId;
        this.displayName = displayName;
        this.cameraController = new IdeCameraController(pos);
    }

    @Override
    protected void init() {
        int leftX = MARGIN;
        int leftW = this.width / 2 - MARGIN - ROW_GAP;
        int saveRowY = this.height - MARGIN - BUTTON_HEIGHT;
        int debugRowY = saveRowY - BUTTON_HEIGHT - ROW_GAP;
        editorTop = TOP_Y;
        editorHeight = debugRowY - ROW_GAP - TOP_Y;

        editor = new DebugEditBox(this.font, leftX + GUTTER_WIDTH, editorTop, leftW - GUTTER_WIDTH, editorHeight,
                Component.translatable("gui.micradrone.ide_screen.editor_placeholder"),
                Component.translatable("gui.micradrone.ide_screen.editor"));
        editor.setCharacterLimit(DroneControllerBlockEntity.MAX_SCRIPT_CHARS);
        editor.setValue(editorText);
        editor.setValueListener(text -> editorText = text);
        editor.setBreakpointLines(breakpoints);
        addRenderableWidget(editor);

        int debugW = (leftW - 3 * ROW_GAP) / 4;
        pauseResumeButton = addRenderableWidget(Button.builder(pauseResumeLabel(), b -> PacketDistributor.sendToServer(
                        new DebugCommandPayload(pos, debugState == DebugStatePayload.STATE_PAUSED
                                ? DebugCommandPayload.COMMAND_RESUME : DebugCommandPayload.COMMAND_PAUSE)))
                .bounds(leftX, debugRowY, debugW, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.debug_step"),
                        b -> PacketDistributor.sendToServer(new DebugCommandPayload(pos, DebugCommandPayload.COMMAND_STEP)))
                .bounds(leftX + debugW + ROW_GAP, debugRowY, debugW, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.debug_step_out"),
                        b -> PacketDistributor.sendToServer(new DebugCommandPayload(pos, DebugCommandPayload.COMMAND_STEP_OUT)))
                .bounds(leftX + 2 * (debugW + ROW_GAP), debugRowY, debugW, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.debug_stop"),
                        b -> PacketDistributor.sendToServer(new StopScriptPayload(pos)))
                .bounds(leftX + 3 * (debugW + ROW_GAP), debugRowY, debugW, BUTTON_HEIGHT).build());

        int buttonW = (leftW - 3 * ROW_GAP) / 4;
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.save"), b -> save())
                .bounds(leftX, saveRowY, buttonW, BUTTON_HEIGHT).build());
        // Plain Run: runs the SAVED script without touching unsaved editor changes - handy when
        // re-running a debug session repeatedly.
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.run"),
                        b -> PacketDistributor.sendToServer(new RunScriptPayload(pos, scriptId)))
                .bounds(leftX + buttonW + ROW_GAP, saveRowY, buttonW, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.save_run"), b -> {
                    save();
                    PacketDistributor.sendToServer(new RunScriptPayload(pos, scriptId));
                })
                .bounds(leftX + 2 * (buttonW + ROW_GAP), saveRowY, buttonW, BUTTON_HEIGHT).build());
        listButton = addRenderableWidget(Button.builder(listButtonLabel(), b -> toggleListMode())
                .bounds(leftX + 3 * (buttonW + ROW_GAP), saveRowY, buttonW, BUTTON_HEIGHT).build());

        if (listMode) {
            initListModeWidgets(listPanelX(), listPanelWidth());
        }

        if (this.minecraft != null && this.minecraft.level != null) {
            rescanPlot();
            cameraController.update(this.minecraft, bounds); // no first-frame flash from the player's own view
        }

        if (!logRequested) {
            logRequested = true;
            PacketDistributor.sendToServer(new RequestLogPayload(pos));
        }
        if (!sourceRequested && !scriptId.isEmpty()) {
            sourceRequested = true;
            PacketDistributor.sendToServer(new RequestScriptSourcePayload(pos, scriptId));
        }
    }

    /** Left edge of the right-half list-mode panel - shared by {@link #init()} and rendering so they never drift apart. */
    private int listPanelX() {
        return MARGIN + (this.width / 2 - MARGIN - ROW_GAP) + ROW_GAP;
    }

    private int listPanelWidth() {
        return this.width - MARGIN - listPanelX();
    }

    /** Builds the right-half script-picker panel: points text (drawn in {@link #render}), list, description, log. */
    private void initListModeWidgets(int rightX, int rightW) {
        int y = editorTop + POINTS_HEIGHT;
        scriptList = new ScriptListWidget(Minecraft.getInstance(), rightW, LIST_HEIGHT, y, 16);
        scriptList.setX(rightX);
        scriptList.replaceEntries(availableScripts);
        scriptList.selectId(scriptId);
        addRenderableWidget(scriptList);

        y += LIST_HEIGHT + ROW_GAP;
        descriptionBox = new MultiLineEditBox(this.font, rightX, y, rightW, DESCRIPTION_HEIGHT,
                Component.translatable("gui.micradrone.drone_screen.script_description_placeholder"),
                Component.translatable("gui.micradrone.drone_screen.script_description"));
        descriptionBox.setValue(scriptList.selectedDescription());
        addRenderableWidget(descriptionBox);

        y += DESCRIPTION_HEIGHT + ROW_GAP;
        int logHeight = editorTop + editorHeight - y;
        logBox = new MultiLineEditBox(this.font, rightX, y, rightW, logHeight,
                Component.translatable("gui.micradrone.drone_screen.log_placeholder"),
                Component.translatable("gui.micradrone.drone_screen.log"));
        logBox.setValue(String.join("\n", logLines));
        addRenderableWidget(logBox);
    }

    private void toggleListMode() {
        listMode = !listMode;
        rebuildWidgets();
    }

    private Component listButtonLabel() {
        return Component.translatable(listMode
                ? "gui.micradrone.ide_screen.list_close" : "gui.micradrone.ide_screen.list_open");
    }

    /**
     * Switches the editor to {@code entry} (list-mode click): tells the server it's now selected,
     * adopts its id/name locally right away (no need to wait for a round trip - the list already
     * has them), reloads the editor from it, and closes list mode.
     */
    private void selectAndEdit(ScriptEntry entry) {
        PacketDistributor.sendToServer(new SelectScriptPayload(pos, entry.id()));
        scriptId = entry.id();
        displayName = entry.displayName();
        editorText = "";
        sourceRequested = true;
        PacketDistributor.sendToServer(new RequestScriptSourcePayload(pos, scriptId));
        listMode = false;
        rebuildWidgets();
    }

    /**
     * Re-resolves plot size/direction by running the same corner-marker scan the server uses
     * against the client-side level (blocks are synced, so it finds the same plot - no networking).
     */
    private void rescanPlot() {
        bounds = CornerMarkerScan.scan(
                (dx, dy, dz) -> this.minecraft.level.getBlockState(pos.offset(dx, dy, dz)).is(MicraDrone.CORNER_MARKER_BLOCK.get()),
                (dx, dy, dz) -> DroneControllerBlockEntity.isDirtLike(this.minecraft.level.getBlockState(pos.offset(dx, dy, dz))),
                DroneControllerBlockEntity.MAX_MARKER_SCAN_DISTANCE,
                DroneControllerBlockEntity.MAX_MARKER_SCAN_Y_TOLERANCE,
                DroneControllerBlockEntity.DEFAULT_WORLD_SIZE);
    }

    /** Called from {@code MicraDroneClient} when the requested script source arrives. */
    public void updateSource(BlockPos sourcePos, String sourceScriptName, String source) {
        if (sourcePos.equals(this.pos) && sourceScriptName.equals(this.scriptId)) {
            editorText = source;
            editor.setValue(source);
        }
    }

    /**
     * Called from {@code MicraDroneClient} on every {@code DroneLogPayload} (list refresh, log
     * lines, points) - the same snapshot {@code DroneScreen} used to consume on its own. The first
     * one to arrive after opening on an unresolved {@link #scriptId} (right-click with an empty
     * hand - no fixed slot id exists anymore) resolves it to the server's current selection.
     */
    public void updateLog(BlockPos sourcePos, List<String> lines, Map<String, Long> newPointsByCrop,
            List<ScriptEntry> scripts, String selectedScript, String alias) {
        if (!sourcePos.equals(this.pos)) {
            return;
        }
        logLines = lines;
        pointsByCrop = newPointsByCrop;
        selectedScriptFromServer = selectedScript;
        if (!scripts.isEmpty()) {
            availableScripts = scripts;
        }

        if (scriptId.isEmpty() && !selectedScriptFromServer.isEmpty()) {
            scriptId = selectedScriptFromServer;
            displayName = availableScripts.stream()
                    .filter(entry -> entry.id().equals(scriptId))
                    .findFirst()
                    .map(ScriptEntry::displayName)
                    .orElse(scriptId);
            sourceRequested = true;
            PacketDistributor.sendToServer(new RequestScriptSourcePayload(pos, scriptId));
        }

        if (listMode && scriptList != null) {
            scriptList.replaceEntries(availableScripts);
            scriptList.selectId(scriptId);
            if (descriptionBox != null) {
                descriptionBox.setValue(scriptList.selectedDescription());
            }
            if (logBox != null) {
                logBox.setValue(String.join("\n", logLines));
            }
        }
    }

    /** Called from {@code MicraDroneClient} when a DebugStatePayload arrives for this controller. */
    public void updateDebugState(BlockPos sourcePos, int state, int currentLine, List<Integer> serverBreakpoints) {
        if (!sourcePos.equals(this.pos)) {
            return;
        }
        debugState = state;
        breakpoints.clear();
        breakpoints.addAll(serverBreakpoints);
        editor.setBreakpointLines(breakpoints);
        editor.setCurrentLine(state == DebugStatePayload.STATE_IDLE ? 0 : currentLine);
        pauseResumeButton.setMessage(pauseResumeLabel());
    }

    private Component pauseResumeLabel() {
        return Component.translatable(debugState == DebugStatePayload.STATE_PAUSED
                ? "gui.micradrone.ide_screen.debug_resume" : "gui.micradrone.ide_screen.debug_pause");
    }

    private void save() {
        PacketDistributor.sendToServer(new SaveScriptPayload(pos, scriptId, editorText));
    }

    private int lineCount() {
        int lines = 1;
        for (int i = 0; i < editorText.length(); i++) {
            if (editorText.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /** Gutter clicks toggle a breakpoint on the clicked line; everything else goes to the widgets. */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= MARGIN && mouseX < MARGIN + GUTTER_WIDTH
                && mouseY >= editorTop && mouseY < editorTop + editorHeight) {
            int line = (int) ((mouseY - editorTop - editor.gutterTopPadding() + editor.gutterScroll())
                    / DebugEditBox.LINE_HEIGHT) + 1;
            if (line >= 1 && line <= lineCount()) {
                if (!breakpoints.remove(line)) {
                    breakpoints.add(line);
                }
                editor.setBreakpointLines(breakpoints);
                PacketDistributor.sendToServer(new SetBreakpointsPayload(pos, breakpoints.stream().sorted().toList()));
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.minecraft == null || this.minecraft.level == null) {
            return;
        }
        tickCounter++;
        if (tickCounter % RESCAN_INTERVAL_TICKS == 0) {
            rescanPlot();
        }
        // Every tick: follows corner-marker changes, FOV changes, and window resizes. Harmless to
        // keep driving while list mode hides it behind the opaque panel fill.
        cameraController.update(this.minecraft, bounds);
    }

    /**
     * The vanilla blur + dark overlay would hide the whole point of the camera view - skip it so
     * the world shows through crisply. List mode instead paints its own opaque panel over the
     * right half (see {@link #render}), since the picker needs a readable background.
     */
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
    }

    /** Called when this screen is closed or replaced - the viewpoint must always come back. */
    @Override
    public void removed() {
        if (this.minecraft != null) {
            cameraController.restore(this.minecraft);
        }
        super.removed();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (listMode) {
            guiGraphics.fill(listPanelX() - ROW_GAP, 0, this.width, this.height, 0xE0101010);
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font,
                Component.translatable("gui.micradrone.ide_screen.heading", displayName),
                this.width / 2, MARGIN, 0xFFFFFF);
        renderGutter(guiGraphics);
        if (listMode) {
            renderPointsLines(guiGraphics);
        }
    }

    private void renderPointsLines(GuiGraphics guiGraphics) {
        int rightX = listPanelX();
        int rightW = listPanelWidth();
        int y = editorTop;
        for (Map.Entry<String, Long> entry : new TreeMap<>(pointsByCrop).entrySet()) {
            guiGraphics.drawString(this.font, cropDisplayName(entry.getKey()) + ": " + entry.getValue(),
                    rightX + rightW / 2 - this.font.width(cropDisplayName(entry.getKey()) + ": " + entry.getValue()) / 2,
                    y, 0xFFFFFF);
            y += 9;
        }
    }

    private static String cropDisplayName(String cropName) {
        return cropName.isEmpty() ? cropName
                : Character.toUpperCase(cropName.charAt(0)) + cropName.substring(1).toLowerCase(Locale.ROOT);
    }

    /** Line numbers + breakpoint dots, scrolled in sync with the editor text. */
    private void renderGutter(GuiGraphics guiGraphics) {
        guiGraphics.fill(MARGIN, editorTop, MARGIN + GUTTER_WIDTH, editorTop + editorHeight, 0xE0101010);
        int firstTextY = editorTop + editor.gutterTopPadding();
        int scroll = (int) editor.gutterScroll();
        int total = lineCount();
        for (int line = 1; line <= total; line++) {
            int y = firstTextY + (line - 1) * DebugEditBox.LINE_HEIGHT - scroll;
            if (y < editorTop || y + DebugEditBox.LINE_HEIGHT > editorTop + editorHeight) {
                continue;
            }
            if (breakpoints.contains(line)) {
                guiGraphics.fill(MARGIN + 1, y + 1, MARGIN + 6, y + 6, 0xFFCC3333);
            }
            String label = String.valueOf(line);
            guiGraphics.drawString(this.font, label,
                    MARGIN + GUTTER_WIDTH - 2 - this.font.width(label), y, 0xFF808080, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Scrollable list of this controller's available scripts (library scrolls, plain text - the
     * jukebox slot marker is gone with the slot itself). Selecting a row calls back into
     * {@link #selectAndEdit}; server order is kept as-is (chest scrolls in chest/slot order).
     */
    private final class ScriptListWidget extends ObjectSelectionList<ScriptListWidget.Row> {
        ScriptListWidget(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        void replaceEntries(List<ScriptEntry> entries) {
            String previouslySelected = scriptId;
            clearEntries();
            entries.forEach(entry -> addEntry(new Row(entry)));
            selectId(previouslySelected);
            if (getSelected() == null && getItemCount() > 0) {
                setSelected(getEntry(0));
            }
        }

        void selectId(String id) {
            for (int i = 0; i < getItemCount(); i++) {
                Row row = getEntry(i);
                if (row.entry.id().equals(id)) {
                    setSelected(row);
                    return;
                }
            }
        }

        String selectedDescription() {
            Row selected = getSelected();
            return selected != null ? selected.entry.description() : "";
        }

        @Override
        public int getRowWidth() {
            return this.width - 10;
        }

        final class Row extends ObjectSelectionList.Entry<Row> {
            private final ScriptEntry entry;
            private final Component label;

            Row(ScriptEntry entry) {
                this.entry = entry;
                // ⚑ marks a scroll in a library chest; plain text is an on-disk file.
                String name = entry.displayName();
                this.label = Component.literal(entry.id().startsWith("scroll:") ? "⚑ " + name : name);
            }

            @Override
            public Component getNarration() {
                return Component.literal(entry.displayName() + ": " + entry.description());
            }

            @Override
            public void render(GuiGraphics guiGraphics, int index, int top, int left, int width, int height,
                    int mouseX, int mouseY, boolean hovering, float partialTick) {
                guiGraphics.drawString(IdeScreen.this.font, label, left + 2, top + (height - 8) / 2, 0xFFFFFF);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                ScriptListWidget.this.setSelected(this);
                if (!this.entry.id().equals(IdeScreen.this.scriptId)) {
                    IdeScreen.this.selectAndEdit(this.entry);
                } else {
                    IdeScreen.this.listMode = false;
                    IdeScreen.this.rebuildWidgets();
                }
                return true;
            }
        }
    }
}
