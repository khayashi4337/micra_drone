package io.github.khayashi4337.micradrone.client;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.github.khayashi4337.micradrone.MicraDroneClient;
import io.github.khayashi4337.micradrone.drone.CornerMarkerScan;
import io.github.khayashi4337.micradrone.drone.DroneControllerBlockEntity;
import io.github.khayashi4337.micradrone.lang.CommandNames;
import io.github.khayashi4337.micradrone.lang.Lexer;
import io.github.khayashi4337.micradrone.drone.net.DebugCommandPayload;
import io.github.khayashi4337.micradrone.drone.net.DebugStatePayload;
import io.github.khayashi4337.micradrone.drone.net.RenameScriptPayload;
import io.github.khayashi4337.micradrone.drone.net.RequestLogPayload;
import io.github.khayashi4337.micradrone.drone.net.RequestScriptSourcePayload;
import io.github.khayashi4337.micradrone.drone.net.RunScriptPayload;
import io.github.khayashi4337.micradrone.drone.net.SaveScriptPayload;
import io.github.khayashi4337.micradrone.drone.net.ScriptEntry;
import io.github.khayashi4337.micradrone.drone.net.SelectScriptPayload;
import io.github.khayashi4337.micradrone.drone.net.SetBreakpointsPayload;
import io.github.khayashi4337.micradrone.drone.net.StopScriptPayload;
import io.github.khayashi4337.micradrone.drone.net.StopViewingPayload;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AnvilMenu;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Fullscreen script IDE (issue #6) - the controller's ONLY screen (GUI-reduction follow-up
 * dissolved the separate list/log screen, {@code DroneScreen}, into this one's right half as a
 * toggleable "list mode" - see {@link #listMode}). Left half is a {@link MultiLineEditBox} editor
 * for one of the controller's scripts; the right half is normally the real field seen from
 * straight above (the game's own camera hovers over the plot, {@link IdeCameraController}, 林さん's
 * "float the viewpoint" idea) - pressing List swaps it for the script picker (script list +
 * description + log, what {@code DroneScreen} used to show on its own) instead, since hiding the
 * live view briefly to pick a file doesn't hurt the experience (林さん's call). Per-crop points are
 * a separate full-width HUD line under the heading, shown in both modes - see {@link #renderPointsHud}.
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
    /** Below the heading - the points HUD line (visible in both camera and list mode, see {@link #renderPointsHud}). */
    private static final int POINTS_Y = MARGIN + 12;
    private static final int TOP_Y = 34;
    /** Top-right corner, same row as the heading - opens the unlock shop without a trip to the Corner Marker. */
    private static final int SHOP_BUTTON_WIDTH = 50;
    private static final int SHOP_BUTTON_HEIGHT = 14;
    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW_GAP = 4;
    /** Icon-only Run control above the editor (green square, white triangle) - see {@link PlayButton}. */
    private static final int PLAY_BUTTON_SIZE = 20;
    /** Tight gap between the Play/Step icon buttons on the title bar - they read as one control pair. */
    private static final int ICON_GAP = 2;
    /** Width of the line-number/breakpoint gutter to the left of the editor. */
    private static final int GUTTER_WIDTH = 20;
    /** How often (client ticks) the plot size/direction is re-resolved from the blocks. */
    private static final int RESCAN_INTERVAL_TICKS = 20;
    // List-mode panel (right half) sub-region heights, top to bottom: script list, description,
    // then log fills whatever's left.
    private static final int LIST_HEIGHT = 90;
    private static final int DESCRIPTION_HEIGHT = 28;

    // Command autocomplete popup - see refreshAutocomplete/acceptAutocomplete/renderAutocompletePopup.
    private static final List<String> AUTOCOMPLETE_CANDIDATES =
            Stream.concat(CommandNames.ALL.stream(), Lexer.keywords().stream()).sorted().toList();
    private static final int AUTOCOMPLETE_MAX_ROWS = 8;
    private static final int AUTOCOMPLETE_ROW_HEIGHT = 10;
    private static final int AUTOCOMPLETE_BACKGROUND = 0xF0202020;
    private static final int AUTOCOMPLETE_SELECTED_BACKGROUND = 0xF0355C7D;

    /** Double-clicking the title renames the script - max gap between the two clicks. */
    private static final long DOUBLE_CLICK_WINDOW_MS = 500;

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

    // Autocomplete popup state - see refreshAutocomplete/renderAutocompletePopup. Recomputed from
    // the editor's real caret every frame rather than pushed from an edit callback, so the popup
    // tracks wherever the caret actually is instead of a spot it was at when some edit landed.
    // Position fields are filled in by the same render pass and read back by mouseClicked (safe: a
    // click can't land before the popup it hits has been drawn at least once).
    private String autocompleteWord = "";
    private List<String> autocompleteMatches = List.of();
    private int autocompleteSelected;
    /**
     * Set when the player closes the popup - Escape, a click outside it, or accepting a suggestion -
     * and cleared only by the next actual edit (see the value listener in {@link #init}). Keyed on
     * edits rather than on the word under the caret so that merely moving the caret back into a
     * half-typed word doesn't reopen a popup the player just dismissed.
     */
    private boolean autocompleteDismissed;
    private int autocompletePopupX;
    private int autocompletePopupY;
    private int autocompletePopupWidth;

    // Title rename (double-click): non-null while the inline EditBox is showing.
    private EditBox renameBox;
    private long lastTitleClickAtMs = -1;

    // Gutter geometry, computed in init() and reused by render()/mouseClicked().
    private int editorTop;
    private int editorHeight;
    /** Right edge of the editor title bar (see {@link #renderEditorTitleBar}), computed in init(). */
    private int titleBarRight;

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
        editorTop = TOP_Y + PLAY_BUTTON_SIZE + ROW_GAP;
        editorHeight = debugRowY - ROW_GAP - editorTop;
        titleBarRight = leftX + leftW;

        // Icon-only Run + Step controls, sitting side by side on the editor's title bar (see
        // renderEditorTitleBar) - matches the reference game's own title bar. Run
        // plays the SAVED script without touching unsaved editor changes, same behavior the old text
        // "Run" button had; "Save & Run" below still does both. Step moved up here from the debug
        // row below (was a duplicate control once an icon existed), so the debug row is now 3-wide,
        // not 4.
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.run"),
                        b -> PacketDistributor.sendToServer(new RunScriptPayload(pos, scriptId)))
                .bounds(leftX + GUTTER_WIDTH, TOP_Y, PLAY_BUTTON_SIZE, PLAY_BUTTON_SIZE)
                .build(PlayButton::new));
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.debug_step"),
                        b -> PacketDistributor.sendToServer(new DebugCommandPayload(pos, DebugCommandPayload.COMMAND_STEP)))
                .bounds(leftX + GUTTER_WIDTH + PLAY_BUTTON_SIZE + ICON_GAP, TOP_Y, PLAY_BUTTON_SIZE, PLAY_BUTTON_SIZE)
                .build(StepButton::new));

        editor = new DebugEditBox(this.font, leftX + GUTTER_WIDTH, editorTop, leftW - GUTTER_WIDTH, editorHeight,
                Component.translatable("gui.micradrone.ide_screen.editor_placeholder"),
                Component.translatable("gui.micradrone.ide_screen.editor"));
        editor.setCharacterLimit(DroneControllerBlockEntity.MAX_SCRIPT_CHARS);
        editor.setValue(editorText);
        editor.setValueListener(text -> {
            editorText = text;
            // Typing is the one thing that brings a dismissed popup back - see refreshAutocomplete.
            autocompleteDismissed = false;
        });
        editor.setBreakpointLines(breakpoints);
        addRenderableWidget(editor);

        // 3-way now: Step moved up to the title bar icon row above (see the StepButton added earlier
        // in this method).
        int debugW = (leftW - 2 * ROW_GAP) / 3;
        pauseResumeButton = addRenderableWidget(Button.builder(pauseResumeLabel(), b -> PacketDistributor.sendToServer(
                        new DebugCommandPayload(pos, debugState == DebugStatePayload.STATE_PAUSED
                                ? DebugCommandPayload.COMMAND_RESUME : DebugCommandPayload.COMMAND_PAUSE)))
                .bounds(leftX, debugRowY, debugW, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.debug_step_out"),
                        b -> PacketDistributor.sendToServer(new DebugCommandPayload(pos, DebugCommandPayload.COMMAND_STEP_OUT)))
                .bounds(leftX + debugW + ROW_GAP, debugRowY, debugW, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.debug_stop"),
                        b -> PacketDistributor.sendToServer(new StopScriptPayload(pos)))
                .bounds(leftX + 2 * (debugW + ROW_GAP), debugRowY, debugW, BUTTON_HEIGHT).build());

        // 3-way now: the Run icon moved above the editor (see the PlayButton added earlier in this method).
        int buttonW = (leftW - 2 * ROW_GAP) / 3;
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.save"), b -> save())
                .bounds(leftX, saveRowY, buttonW, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.save_run"), b -> {
                    save();
                    PacketDistributor.sendToServer(new RunScriptPayload(pos, scriptId));
                })
                .bounds(leftX + buttonW + ROW_GAP, saveRowY, buttonW, BUTTON_HEIGHT).build());
        listButton = addRenderableWidget(Button.builder(listButtonLabel(), b -> toggleListMode())
                .bounds(leftX + 2 * (buttonW + ROW_GAP), saveRowY, buttonW, BUTTON_HEIGHT).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.shop"),
                        b -> MicraDroneClient.openShopScreen(pos))
                .bounds(this.width - MARGIN - SHOP_BUTTON_WIDTH, MARGIN, SHOP_BUTTON_WIDTH, SHOP_BUTTON_HEIGHT)
                .build());

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

    /** Builds the right-half script-picker panel: script list, description, log (points HUD is drawn full-width in {@link #render}). */
    private void initListModeWidgets(int rightX, int rightW) {
        int y = editorTop;
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
        autocompleteMatches = List.of();
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
            autocompleteMatches = List.of();
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
        } else if (!scriptId.isEmpty()) {
            // Keeps the title in sync with the server's authoritative name after a rename - the
            // round trip lands here, in the very next snapshot (see commitRename/renameScript).
            availableScripts.stream()
                    .filter(entry -> entry.id().equals(scriptId))
                    .findFirst()
                    .ifPresent(entry -> displayName = entry.displayName());
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

    /** Left edge of the title text within the title bar - shared by rendering, the click hit-test, and the rename box's position. */
    private int titleTextX() {
        return MARGIN + GUTTER_WIDTH + 2 * PLAY_BUTTON_SIZE + ICON_GAP + ROW_GAP;
    }

    /** Double-click the title to rename the script - an inline EditBox replaces the plain text. */
    private void startRename() {
        int x = titleTextX();
        int width = Math.min(titleBarRight - x - ROW_GAP, 150);
        renameBox = new EditBox(this.font, x, TOP_Y + 2, width, PLAY_BUTTON_SIZE - 4, Component.literal(""));
        renameBox.setMaxLength(AnvilMenu.MAX_NAME_LENGTH);
        renameBox.setValue(displayName);
        addRenderableWidget(renameBox);
        this.setFocused(renameBox);
    }

    /** Enter: sends the new name (if it actually changed) and closes the box; the server confirms via the next log snapshot. */
    private void commitRename() {
        String newName = renameBox.getValue().trim();
        if (!newName.isEmpty() && !newName.equals(displayName)) {
            PacketDistributor.sendToServer(new RenameScriptPayload(pos, scriptId, newName));
            displayName = newName;
        }
        cancelRename();
    }

    /** Escape, or clicking away, or losing focus some other way: closes the box without sending anything. */
    private void cancelRename() {
        if (renameBox != null) {
            removeWidget(renameBox);
            renameBox = null;
            this.setFocused(null);
        }
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

    /**
     * Re-reads the word at the caret and refilters the suggestions - called once per frame from
     * {@link #render}, so the popup follows the caret wherever it goes. Only ever suggests while the
     * editor itself holds the keyboard: otherwise a popup left over from earlier would keep
     * swallowing Up/Down/Enter meant for whatever the player moved on to.
     */
    private void refreshAutocomplete() {
        String word = editor.isFocused() ? editor.wordBeforeCursor() : "";
        if (!word.equals(autocompleteWord)) {
            autocompleteWord = word;
            autocompleteSelected = 0;
        }
        autocompleteMatches = word.isEmpty() || autocompleteDismissed ? List.of()
                : AUTOCOMPLETE_CANDIDATES.stream()
                        .filter(candidate -> candidate.startsWith(word) && !candidate.equals(word))
                        .limit(AUTOCOMPLETE_MAX_ROWS)
                        .toList();
    }

    /**
     * Replaces the word at the caret with {@code candidate}, or reports false and changes nothing
     * when the popup turns out to be stale.
     *
     * <p>Keystrokes arrive in batches rather than one per frame, so the list being read here can
     * still describe the word as it was before an earlier key in the same batch edited it - a
     * Backspace immediately followed by Enter would otherwise paste a suggestion for a word that no
     * longer exists. Re-checking the live caret catches exactly that; returning false lets the
     * caller leave the key unconsumed so it does its normal job instead.
     */
    private boolean acceptAutocomplete(String candidate) {
        String word = editor.wordBeforeCursor();
        if (word.isEmpty() || !candidate.startsWith(word)) {
            autocompleteMatches = List.of();
            return false;
        }
        editor.replaceWordBeforeCursor(candidate);
        autocompleteMatches = List.of();
        autocompleteDismissed = true;
        return true;
    }

    /**
     * Autocomplete popup clicks pick that row; a click anywhere else while it's showing just
     * dismisses it (doesn't consume the click - it still reaches whatever else is under it).
     * Double-clicking the title text starts a rename; a click outside the rename box while renaming
     * cancels it (only Enter commits - see {@link #keyPressed}). Gutter clicks toggle
     * a breakpoint on the clicked line; everything else goes to the widgets.
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (renameBox != null) {
            boolean withinBox = mouseX >= renameBox.getX() && mouseX < renameBox.getX() + renameBox.getWidth()
                    && mouseY >= renameBox.getY() && mouseY < renameBox.getY() + renameBox.getHeight();
            if (!withinBox) {
                cancelRename();
            }
        } else if (button == 0) {
            int textX = titleTextX();
            int textY = TOP_Y + (PLAY_BUTTON_SIZE - this.font.lineHeight) / 2;
            int textWidth = this.font.width(displayName);
            if (mouseX >= textX && mouseX < textX + textWidth
                    && mouseY >= textY && mouseY < textY + this.font.lineHeight) {
                long now = Util.getMillis();
                if (now - lastTitleClickAtMs <= DOUBLE_CLICK_WINDOW_MS) {
                    startRename();
                } else {
                    lastTitleClickAtMs = now;
                }
                return true;
            }
        }
        if (!autocompleteMatches.isEmpty()) {
            int popupHeight = autocompleteMatches.size() * AUTOCOMPLETE_ROW_HEIGHT;
            if (button == 0 && mouseX >= autocompletePopupX && mouseX < autocompletePopupX + autocompletePopupWidth
                    && mouseY >= autocompletePopupY && mouseY < autocompletePopupY + popupHeight) {
                int row = (int) ((mouseY - autocompletePopupY) / AUTOCOMPLETE_ROW_HEIGHT);
                acceptAutocomplete(autocompleteMatches.get(row));
                return true;
            }
            autocompleteMatches = List.of();
            autocompleteDismissed = true; // stays shut until the next keystroke, even if the caret lands mid-word
        }
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

    /**
     * While renaming (title double-clicked), Enter commits and Escape cancels - both consumed
     * before reaching the rename box itself; everything else (typing, arrows, backspace) still
     * flows through to it via {@code super.keyPressed} + {@link #setFocused}.
     *
     * <p>Otherwise, while the autocomplete popup is showing <em>and the editor holds the
     * keyboard</em>, Up/Down move the selection, Escape dismisses it, and Tab/Enter accept it
     * (matching vanilla's own {@code CommandSuggestions.SuggestionsList} bindings, GLFW key codes
     * via {@link GLFW} rather than raw numbers). Up/Down/Escape are consumed outright; Tab/Enter
     * only when the suggestion actually applied - {@link #acceptAutocomplete} refuses a stale one,
     * and the key then carries on to the editor so it still does its ordinary job. Everything else,
     * including all of these once the popup is empty or the editor is unfocused, goes to the
     * focused widget as usual.
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (renameBox != null) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    commitRename();
                    return true;
                }
                case GLFW.GLFW_KEY_ESCAPE -> {
                    cancelRename();
                    return true;
                }
                default -> { }
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (!autocompleteMatches.isEmpty() && editor.isFocused()) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_UP -> {
                    autocompleteSelected = Math.floorMod(autocompleteSelected - 1, autocompleteMatches.size());
                    return true;
                }
                case GLFW.GLFW_KEY_DOWN -> {
                    autocompleteSelected = Math.floorMod(autocompleteSelected + 1, autocompleteMatches.size());
                    return true;
                }
                case GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    // Falls through to the editor when the suggestion turned out to be stale, so a
                    // batched Backspace-then-Enter still inserts the newline the player asked for.
                    if (acceptAutocomplete(autocompleteMatches.get(autocompleteSelected))) {
                        return true;
                    }
                }
                case GLFW.GLFW_KEY_ESCAPE -> {
                    autocompleteMatches = List.of();
                    autocompleteDismissed = true;
                    return true;
                }
                default -> { }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void tick() {
        super.tick();
        // Failsafe: if focus was stolen or lost some other way while renaming, don't leave an
        // orphaned, unfocused rename box on screen - close it (matches clicking away, see mouseClicked).
        if (renameBox != null && !renameBox.isFocused()) {
            cancelRename();
        }
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
        // Tell the server to stop pushing this controller's log/debug updates to us. Harmless when
        // this screen is being replaced by another one on the same controller: that screen's own
        // opening request re-registers us right after.
        PacketDistributor.sendToServer(new StopViewingPayload(pos));
        super.removed();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (listMode) {
            guiGraphics.fill(listPanelX() - ROW_GAP, 0, this.width, this.height, 0xE0101010);
        }
        renderEditorTitleBar(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font,
                Component.translatable("gui.micradrone.ide_screen.heading", displayName),
                this.width / 2, MARGIN, 0xFFFFFF);
        renderPointsHud(guiGraphics);
        renderGutter(guiGraphics);
        refreshAutocomplete();
        renderAutocompletePopup(guiGraphics);
    }

    /**
     * Title-bar strip spanning the editor's width, drawn behind the Play icon so it reads as one
     * header row (icon + script name) rather than a floating button (like a code editor's title
     * bar/toolbar, room for more controls later). The Play button
     * widget itself is rendered afterward by {@code super.render}, painting over this bar's left
     * end so the icon visually sits on top of it.
     */
    private void renderEditorTitleBar(GuiGraphics guiGraphics) {
        int barLeft = MARGIN + GUTTER_WIDTH;
        guiGraphics.fill(barLeft, TOP_Y, titleBarRight, TOP_Y + PLAY_BUTTON_SIZE, 0xE0101010);
        if (renameBox == null) {
            int textY = TOP_Y + (PLAY_BUTTON_SIZE - this.font.lineHeight) / 2;
            guiGraphics.drawString(this.font, displayName, titleTextX(), textY, 0xFFFFFF, false);
        }
    }

    /**
     * Per-crop harvest totals as one line spanning the top of the screen, matching the persistent
     * resource HUD in the reference game (The Farmer Was Replaced) - 林さん's request. Shown in both
     * camera and list mode (not tucked inside list mode only, unlike the old {@code DroneScreen}-era
     * placement) since it's exactly the kind of at-a-glance status the reference game keeps always
     * visible.
     */
    private void renderPointsHud(GuiGraphics guiGraphics) {
        if (pointsByCrop.isEmpty()) {
            return;
        }
        String text = new TreeMap<>(pointsByCrop).entrySet().stream()
                .map(entry -> cropDisplayName(entry.getKey()) + ": " + entry.getValue())
                .collect(Collectors.joining("   "));
        guiGraphics.drawCenteredString(this.font, text, this.width / 2, POINTS_Y, 0xFFFFFF);
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

    /**
     * Command autocomplete popup, anchored to the editor's real caret ({@link DebugEditBox#cursorScreenX}
     * /{@link DebugEditBox#cursorScreenY}, both derived from the same wrapped rows vanilla itself
     * lays the text out on). Sits just under the caret's row, or above it when that would run off
     * the bottom of the editor box.
     */
    private void renderAutocompletePopup(GuiGraphics guiGraphics) {
        if (autocompleteMatches.isEmpty()) {
            return;
        }
        int popupWidth = autocompleteMatches.stream().mapToInt(this.font::width).max().orElse(0) + 6;
        int popupHeight = autocompleteMatches.size() * AUTOCOMPLETE_ROW_HEIGHT;
        int rowTop = editor.cursorScreenY();
        int x = editor.cursorScreenX();
        int y = rowTop + DebugEditBox.LINE_HEIGHT + 1;
        if (y + popupHeight > editorTop + editorHeight) {
            y = rowTop - popupHeight - 1;
        }
        autocompletePopupX = x;
        autocompletePopupY = y;
        autocompletePopupWidth = popupWidth;

        guiGraphics.fill(x, y, x + popupWidth, y + popupHeight, AUTOCOMPLETE_BACKGROUND);
        for (int i = 0; i < autocompleteMatches.size(); i++) {
            int rowY = y + i * AUTOCOMPLETE_ROW_HEIGHT;
            if (i == autocompleteSelected) {
                guiGraphics.fill(x, rowY, x + popupWidth, rowY + AUTOCOMPLETE_ROW_HEIGHT, AUTOCOMPLETE_SELECTED_BACKGROUND);
            }
            guiGraphics.drawString(this.font, autocompleteMatches.get(i), x + 3, rowY + 1, 0xFFFFFF, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Shared look for the title bar's icon buttons (green square, white glyph) instead of a text
     * label - matches the reference game's (The Farmer Was Replaced) title bar. No existing
     * icon-button precedent in this mod, so it's a plain procedural {@link GuiGraphics#fill} draw
     * rather than a new texture asset - simplest option for flat 2-color icons. Subclasses only
     * need to draw their glyph; the background/hover fill is common.
     */
    private abstract static class IconButton extends Button {
        private static final int BACKGROUND = 0xFF3E9142;
        private static final int BACKGROUND_HOVER = 0xFF57B75B;
        static final int GLYPH_COLOR = 0xFFFFFFFF;

        IconButton(Button.Builder builder) {
            super(builder);
        }

        @Override
        protected final void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();
            int background = isHoveredOrFocused() ? BACKGROUND_HOVER : BACKGROUND;
            guiGraphics.fill(x, y, x + w, y + h, background);
            drawGlyph(guiGraphics, x, y, w, h, background);
        }

        protected abstract void drawGlyph(GuiGraphics guiGraphics, int x, int y, int w, int h, int background);

        /** Fills a right-pointing triangle (built from horizontal strips) inside the given bounds. */
        static void fillTriangle(GuiGraphics guiGraphics, int left, int top, int right, int bottom, int color) {
            int centerY = (top + bottom) / 2;
            int halfHeight = (bottom - top) / 2;
            if (halfHeight <= 0) {
                return;
            }
            for (int row = top; row <= bottom; row++) {
                int distanceFromCenter = Math.abs(row - centerY);
                int rowRight = left + (right - left) * (halfHeight - distanceFromCenter) / halfHeight;
                if (rowRight > left) {
                    guiGraphics.fill(left, row, rowRight, row + 1, color);
                }
            }
        }
    }

    /** Solid triangle - "run/play". */
    private static final class PlayButton extends IconButton {
        private static final int PADDING = 5;

        PlayButton(Button.Builder builder) {
            super(builder);
        }

        @Override
        protected void drawGlyph(GuiGraphics guiGraphics, int x, int y, int w, int h, int background) {
            fillTriangle(guiGraphics, x + PADDING, y + PADDING, x + w - PADDING, y + h - PADDING, GLYPH_COLOR);
        }
    }

    /**
     * Hollow/outline triangle - "step one instruction", distinct from Play's solid triangle. Drawn
     * by filling a full triangle in the glyph color, then punching out a smaller triangle in the
     * button's own current background color on top of it.
     */
    private static final class StepButton extends IconButton {
        private static final int PADDING = 5;
        private static final int OUTLINE = 2;

        StepButton(Button.Builder builder) {
            super(builder);
        }

        @Override
        protected void drawGlyph(GuiGraphics guiGraphics, int x, int y, int w, int h, int background) {
            fillTriangle(guiGraphics, x + PADDING, y + PADDING, x + w - PADDING, y + h - PADDING, GLYPH_COLOR);
            fillTriangle(guiGraphics, x + PADDING + OUTLINE, y + PADDING + OUTLINE,
                    x + w - PADDING - OUTLINE, y + h - PADDING - OUTLINE, background);
        }
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
                // ✎ marks a blank scroll ready to write into (林さんの要望); ⚑ an already-written
                // scroll (library chest or the player's own inventory); plain text is an on-disk file.
                String name = entry.displayName();
                boolean isScrollItem = entry.id().startsWith("scroll:") || entry.id().startsWith("inv:");
                String prefix = entry.isNew() ? "✎ " : isScrollItem ? "⚑ " : "";
                this.label = Component.literal(prefix + name);
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
