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
import io.github.khayashi4337.micradrone.chat.LineDiff;
import io.github.khayashi4337.micradrone.chat.UnsavedDraftStore;
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
    static final int ROW_GAP = 4; // package-private: IdeChatPanel lays out its rows with the same gap
    /** Heading color while an AI change is under review - the same green the added lines use, so the two read as one state. */
    private static final int REVIEW_HEADING_COLOR = 0xFF7EE08A;
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

    /**
     * Unsaved edits, kept only for as long as the client is running (not persisted to disk or the
     * server) so closing the IDE mid-edit - to check something else while paused in the debugger,
     * say - doesn't throw the draft away: reopening on the same controller and script re-requests
     * the source from the server as always, but a pending draft here wins over what comes back.
     * Cleared once a save actually lands, since the draft and the saved copy agree again then.
     * Keyed by dimension + controller position + script id: one controller holds several scripts,
     * and two controllers can share the same coordinates in different dimensions (overworld /
     * nether / end), which a position-only key would silently conflate. The key still carries no
     * save/server identity, so it is cleared entirely on leaving a world/server (see
     * {@code MicraDroneClient}'s constructor) - a stale draft must not resurface against a
     * different save that happens to reuse the same dimension, coordinates and script id.
     */
    private static final UnsavedDraftStore unsavedDrafts = new UnsavedDraftStore();

    /** Drops every pending draft - call on leaving a world/server, see {@link #unsavedDrafts}'s own doc. */
    public static void clearUnsavedDrafts() {
        unsavedDrafts.clear();
    }

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
     * and cleared by the next change to the editor's text (the value listener in {@link #init};
     * that includes loading a different script, not just typing). Keyed on text changes rather than
     * on the word under the caret so that merely moving the caret back into a half-typed word
     * doesn't reopen a popup the player just dismissed.
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
    private Set<String> unlockedCrops = Set.of();
    private ScriptListWidget scriptList;
    private MultiLineEditBox descriptionBox;
    private MultiLineEditBox logBox;

    // The AI chat tab lives in its own class (IdeChatPanel); this screen only hosts it.
    private final IdeChatPanel chatPanel = new IdeChatPanel(new ChatHost());
    // AI-change review (Cursor-style Apply): while reviewDiff is non-null, the editor shows its
    // merged view read-only. "x Reject" beside a block drops that block from reviewDiff; the Chat
    // tab's "Accept rest" applies reviewDiff.acceptedText(), "Reject all" restores
    // reviewOriginalText. See LineDiff for why this replaced the old overwrite-on-Insert.
    private String reviewOriginalText;
    private LineDiff reviewDiff;
    /** The script as it was before Danger ON's last unreviewed apply - non-null while that apply can still be undone. */
    private String lastAutoApplyOriginal;
    /** Per-hunk "x Reject" marker rectangles from the last frame (null = hunk scrolled out of view), for hit-testing. */
    private final List<int[]> reviewHunkMarkerRects = new java.util.ArrayList<>();
    private static final int HUNK_MARKER_PADDING = 3;
    private static final int HUNK_MARKER_INSET = 8;   // clear of the editor's scrollbar
    private static final int HUNK_MARKER_BACKGROUND = 0xE0402020;
    private static final int HUNK_MARKER_TEXT_COLOR = 0xFFFF7070;
    /** Set by removed(): a late CLI reply must not rebuild (and re-aim the camera of) a closed screen. */
    private boolean closed = false;

    private CornerMarkerScan.PlotBounds bounds = new CornerMarkerScan.PlotBounds(
            DroneControllerBlockEntity.DEFAULT_WORLD_SIZE, 1, 1, false, 0);
    private int tickCounter = 0;

    public IdeScreen(BlockPos pos, String scriptId, String displayName) {
        super(Component.translatable("gui.micradrone.ide_screen.title"));
        this.pos = pos;
        this.scriptId = scriptId;
        this.displayName = displayName;
        this.cameraController = new IdeCameraController(pos);
        // Captured once at open time: the screen belongs to the dimension it was opened in, so the
        // key is fixed for its lifetime rather than re-read from Minecraft.level on every keystroke
        // and save (an external state that, in principle, could change under the open screen).
        this.dimensionKey = Minecraft.getInstance().level == null
                ? "" : Minecraft.getInstance().level.dimension().location().toString();
    }

    /** Dimension this screen was opened in (e.g. "minecraft:overworld"), part of {@link #draftKey}. */
    private final String dimensionKey;

    /** {@link #unsavedDrafts} key for the script currently open - see its own doc. */
    private String draftKey() {
        return dimensionKey + "|" + pos.asLong() + "|" + scriptId;
    }

    /**
     * Vanilla's own post-(re)build focus hook: both {@code Screen#init(Minecraft, int, int)} (the
     * very first open) and {@code Screen#rebuildWidgets()} (every {@code List}/{@code Chat} toggle,
     * picking a script from the list, and any other {@code rebuildWidgets()} call in this class)
     * run {@code init()} and then this, in that order - so overriding it is the one place that
     * covers all of them at once. Without it, none of those actions left anything focused, so the
     * very next arrow-key press fell through to {@code Screen}'s own widget-to-widget navigation
     * instead of reaching the editor (real-machine report). {@link #mouseClicked} separately steals
     * focus back after a plain button press that does NOT rebuild (Save, Run, Step, ...) - the two
     * are complementary, not redundant. Not chosen while {@code List}/{@code Chat} owns the right
     * half (nothing in the editor makes sense to type into then).
     */
    @Override
    protected void setInitialFocus() {
        if (editor != null && !listMode && !chatPanel.isOpen()) {
            setInitialFocus(editor);
        }
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

        DebugEditBox previousEditor = editor;
        editor = new DebugEditBox(this.font, leftX + GUTTER_WIDTH, editorTop, leftW - GUTTER_WIDTH, editorHeight,
                Component.translatable("gui.micradrone.ide_screen.editor_placeholder"),
                Component.translatable("gui.micradrone.ide_screen.editor"));
        editor.setCharacterLimit(DroneControllerBlockEntity.MAX_SCRIPT_CHARS);
        editor.setValue(editorText);
        // init() runs again on every List/Chat toggle, every arriving AI reply, and every window
        // resize - all of which build a new editor widget and would otherwise throw the undo history
        // away with the old one. Typing, opening Chat to ask about it, then pressing Ctrl+Z is an
        // ordinary thing to do, so the history has to outlive the widget the same way editorText and
        // the breakpoint set already do. Must come after setValue above, which clears history by
        // design (see DebugEditBox#setValue); adoptHistoryFrom checks the text still matches, so a
        // rebuild that loads a different script genuinely starts fresh.
        editor.adoptHistoryFrom(previousEditor);
        editor.setValueListener(text -> {
            editorText = text;
            // Mid-review the editor shows a merged diff view (red/green markup), not real script text
            // - see beginReview. Its own setValue() also runs through this listener, so without the
            // isReviewing() guard (enforced inside UnsavedDraftStore#record) a closed-mid-review IDE
            // would resurface that markup as if it were the next draft.
            unsavedDrafts.record(draftKey(), text, isReviewing());
            // Typing is the one thing that brings a dismissed popup back - see refreshAutocomplete.
            autocompleteDismissed = false;
        });
        editor.setBreakpointLines(breakpoints);
        if (isReviewing()) {
            applyReviewDecorations(); // a rebuild (List/Chat toggles, a landing reply) recreates the editor
        }
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

        // 4-way now: the Run icon moved above the editor (see the PlayButton added earlier in this
        // method); Chat is the AI chat panel's tab, alongside List (GUI-reduction follow-up).
        int buttonW = (leftW - 3 * ROW_GAP) / 4;
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.save"), b -> save())
                .bounds(leftX, saveRowY, buttonW, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.save_run"), b -> {
                    save();
                    PacketDistributor.sendToServer(new RunScriptPayload(pos, scriptId));
                })
                .bounds(leftX + buttonW + ROW_GAP, saveRowY, buttonW, BUTTON_HEIGHT).build());
        listButton = addRenderableWidget(Button.builder(listButtonLabel(), b -> toggleListMode())
                .bounds(leftX + 2 * (buttonW + ROW_GAP), saveRowY, buttonW, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(chatPanel.tabButtonLabel(), b -> toggleChatMode())
                .bounds(leftX + 3 * (buttonW + ROW_GAP), saveRowY, buttonW, BUTTON_HEIGHT).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.shop"),
                        b -> MicraDroneClient.openShopScreen(pos))
                .bounds(this.width - MARGIN - SHOP_BUTTON_WIDTH, MARGIN, SHOP_BUTTON_WIDTH, SHOP_BUTTON_HEIGHT)
                .build());

        if (listMode) {
            initListModeWidgets(listPanelX(), listPanelWidth());
        } else if (chatPanel.isOpen()) {
            chatPanel.initWidgets(listPanelX(), listPanelWidth(), editorTop, editorTop + editorHeight);
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
        if (listMode) {
            chatPanel.setOpen(false);
        }
        rebuildWidgets();
    }

    private Component listButtonLabel() {
        return Component.translatable(listMode
                ? "gui.micradrone.ide_screen.list_close" : "gui.micradrone.ide_screen.list_open");
    }

    /**
     * Chat is the AI panel's tab, mutually exclusive with List (only one right-half panel shows at
     * a time). Opening it (not closing) also hands the panel the pointer item's pending region
     * selection - see IdeChatPanel#consumePendingRegionIntoInput.
     */
    private void toggleChatMode() {
        boolean turningOn = !chatPanel.isOpen();
        chatPanel.setOpen(turningOn);
        if (turningOn) {
            listMode = false;
        }
        rebuildWidgets();
        if (turningOn) {
            chatPanel.consumePendingRegionIntoInput();
        }
    }

    /** What IdeChatPanel needs from this screen - kept private so none of it leaks into the public API. */
    private final class ChatHost implements IdeChatPanel.Host {
        @Override
        public Minecraft minecraft() {
            return IdeScreen.this.minecraft;
        }

        @Override
        public net.minecraft.client.gui.Font font() {
            return IdeScreen.this.font;
        }

        @Override
        public BlockPos controllerPos() {
            return pos;
        }

        @Override
        public CornerMarkerScan.PlotBounds plotBounds() {
            return bounds;
        }

        @Override
        public String editorText() {
            return editorText;
        }

        @Override
        public void beginReview(String proposed) {
            IdeScreen.this.beginReview(proposed);
        }

        @Override
        public boolean isReviewing() {
            return IdeScreen.this.isReviewing();
        }

        @Override
        public void acceptReview() {
            IdeScreen.this.acceptReview();
        }

        @Override
        public void rejectReview() {
            IdeScreen.this.rejectReview();
        }

        @Override
        public void applyWithoutReview(String proposed) {
            IdeScreen.this.applyWithoutReview(proposed);
        }

        @Override
        public boolean canUndoLastApply() {
            return lastAutoApplyOriginal != null;
        }

        @Override
        public void undoLastApply() {
            IdeScreen.this.undoLastApply();
        }

        @Override
        public List<String> logLines() {
            return logLines;
        }

        @Override
        public Map<String, Long> pointsByCrop() {
            return pointsByCrop;
        }

        @Override
        public Set<String> unlockedCrops() {
            return unlockedCrops;
        }

        @Override
        public <T extends net.minecraft.client.gui.components.events.GuiEventListener
                & net.minecraft.client.gui.components.Renderable
                & net.minecraft.client.gui.narration.NarratableEntry> T addWidget(T widget) {
            return addRenderableWidget(widget);
        }

        @Override
        public void rebuildWidgets() {
            IdeScreen.this.rebuildWidgets();
        }

        @Override
        public boolean isClosed() {
            return closed;
        }
    }

    // ---- Devkit test hooks -------------------------------------------------------------------
    // Called only by a separate, unshipped test-automation companion mod (never part of the
    // distributed jar) so the AI chat panel can be driven deterministically - a JSON API call
    // instead of pixel-guessing 3D camera aim, which real-machine testing found synthetic mouse
    // input can't drive (Minecraft/GLFW reads raw input directly). Every method name ends in
    // "ForTesting" so a reviewer can immediately tell these aren't part of the normal UI flow.

    public boolean isChatModeForTesting() {
        return chatPanel.isOpen();
    }

    public boolean isChatSendInFlightForTesting() {
        return chatPanel.isSendInFlight();
    }

    public String getChatLogForTesting() {
        return chatPanel.transcriptText();
    }

    public String getEditorTextForTesting() {
        return editorText;
    }

    /** The resolved edit/run target - "" while still unresolved (see updateLog). */
    public String getScriptIdForTesting() {
        return scriptId;
    }

    public boolean isReviewingForTesting() {
        return isReviewing();
    }

    public void acceptReviewForTesting() {
        acceptReview();
        rebuildWidgets();
    }

    public void rejectReviewForTesting() {
        rejectReview();
        rebuildWidgets();
    }

    /** Same effect as typing {@code text} into the editor (replaces the whole script). */
    public void setEditorTextForTesting(String text) {
        editorText = text;
        editor.setValue(text); // fires the value listener above, which records the draft itself
    }

    /** Same effect as clicking Save. */
    public void saveForTesting() {
        save();
    }

    /** Same effect as clicking the play button: runs the saved copy of the current script (does not save first). */
    public void runForTesting() {
        PacketDistributor.sendToServer(new RunScriptPayload(pos, scriptId));
    }

    /** The current script list as {@code id<TAB>displayName<TAB>description} lines, for the devkit's /scripts. */
    public List<String> getAvailableScriptsForTesting() {
        return availableScripts.stream()
                .map(entry -> entry.id() + "\t" + entry.displayName() + "\t" + entry.description())
                .toList();
    }

    /** Same effect as clicking {@code id}'s entry in List mode; a no-op for an id not in the list. */
    public void selectScriptForTesting(String id) {
        availableScripts.stream().filter(entry -> entry.id().equals(id)).findFirst().ifPresent(this::selectAndEdit);
    }

    public int getLastAssistantCodeBlockCountForTesting() {
        return chatPanel.codeBlockCount();
    }

    public boolean isDangerModeForTesting() {
        return chatPanel.isDangerMode();
    }

    /** Switches to the Chat tab if it isn't already showing - a no-op otherwise. */
    public void openChatTabForTesting() {
        if (!chatPanel.isOpen()) {
            toggleChatMode();
        }
    }

    /** Types {@code text} into the chat input and sends it, opening the Chat tab first if needed. */
    public void sendChatMessageForTesting(String text) {
        openChatTabForTesting();
        chatPanel.typeAndSend(text);
    }

    /** Opens the review for the reply's Nth code block (block 0 opens on its own when a reply lands). */
    public void insertCodeBlockForTesting(int index) {
        chatPanel.reviewCodeBlock(index);
        rebuildWidgets();
    }

    public int getReviewHunkCountForTesting() {
        return isReviewing() ? reviewDiff.hunks().size() : 0;
    }

    /** Same effect as clicking the "x Reject" marker beside the Nth change block in the editor. */
    public void rejectHunkForTesting(int index) {
        rejectHunk(index);
    }

    /** Same effect as pressing Esc while "AI: thinking" is showing. */
    public void cancelChatForTesting() {
        chatPanel.cancelRoundTrip();
    }

    public boolean canUndoLastApplyForTesting() {
        return lastAutoApplyOriginal != null;
    }

    /** Same effect as clicking "Undo AI change" after a Danger ON apply. */
    public void undoLastApplyForTesting() {
        undoLastApply();
        rebuildWidgets();
    }

    public void setDangerModeForTesting(boolean enabled) {
        chatPanel.setDangerMode(enabled);
    }

    public void compactForTesting() {
        chatPanel.compact();
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
        // The rebuild below hands the new editor widget its predecessor's undo history whenever the
        // text matches (see DebugEditBox#adoptHistoryFrom) - and here it always will, because the
        // line above blanks editorText while the old widget may also be blank. Switching away from
        // a script the player had just emptied would then carry that script's history into this
        // one, and a Ctrl+Z would file its old text as this script's unsaved draft, overwriting
        // what the server is about to send. Only this method knows a genuinely different script is
        // being loaded, so it says so outright.
        editor.clearHistory();
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

    /**
     * Called from {@code MicraDroneClient} when the requested script source arrives. A pending
     * unsaved draft for this exact controller+script (see {@link #unsavedDrafts}) wins over the
     * server's saved copy, so reopening the IDE mid-edit picks up where typing left off.
     */
    public void updateSource(BlockPos sourcePos, String sourceScriptName, String source) {
        if (sourcePos.equals(this.pos) && sourceScriptName.equals(this.scriptId)) {
            editorText = unsavedDrafts.resolve(draftKey(), source);
            editor.setValue(editorText);
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
            Set<String> newUnlockedCrops, List<ScriptEntry> scripts, String selectedScript, String alias) {
        if (!sourcePos.equals(this.pos)) {
            return;
        }
        logLines = lines;
        pointsByCrop = newPointsByCrop;
        unlockedCrops = newUnlockedCrops;
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
        if (isReviewing()) {
            return; // the editor holds the merged review view, not a script - Accept/Reject first (the heading says so)
        }
        unsavedDrafts.forget(draftKey()); // now matches the server's saved copy, nothing left to protect
        PacketDistributor.sendToServer(new SaveScriptPayload(pos, scriptId, editorText));
    }

    // ---- AI-change review ---------------------------------------------------------------------

    boolean isReviewing() {
        return reviewDiff != null;
    }

    /**
     * Shows {@code proposed} as a line diff against the current script, in the editor itself: the
     * original lines the proposal drops stay visible (red), the lines it adds appear in place
     * (green), everything else reads as context. The editor is locked until Accept or Reject so the
     * merged view can't drift from the diff that colors it. A proposal identical to the script is
     * simply a no-op.
     */
    private void beginReview(String proposed) {
        LineDiff diff = LineDiff.between(editorText, proposed);
        if (!diff.hasChanges()) {
            return;
        }
        reviewOriginalText = editorText;
        reviewDiff = diff;
        lastAutoApplyOriginal = null; // a fresh review supersedes any earlier one-step undo
        editor.setValue(diff.mergedText()); // the value listener mirrors this into editorText; endReview restores
        applyReviewDecorations();
    }

    private void applyReviewDecorations() {
        editor.setDiffLines(new HashSet<>(reviewDiff.lineNumbersOf(LineDiff.Kind.ADDED)),
                new HashSet<>(reviewDiff.lineNumbersOf(LineDiff.Kind.REMOVED)));
        editor.setLocked(true);
    }

    /** Accept applies every block not already rejected on its own - not the raw proposal. */
    private void acceptReview() {
        if (isReviewing()) {
            endReview(reviewDiff.acceptedText());
        }
    }

    private void rejectReview() {
        endReview(reviewOriginalText);
    }

    /**
     * Danger ON's counterpart of {@link #beginReview}: the proposal replaces the script outright,
     * and the text it replaced is kept for one {@link #undoLastApply} - the safety net that lets
     * the "just let the AI do it" mode stay recoverable.
     */
    private void applyWithoutReview(String proposed) {
        if (proposed.equals(editorText)) {
            return;
        }
        lastAutoApplyOriginal = editorText;
        editorText = proposed;
        editor.setValue(proposed);
    }

    private void undoLastApply() {
        if (lastAutoApplyOriginal == null) {
            return;
        }
        editorText = lastAutoApplyOriginal;
        editor.setValue(editorText);
        lastAutoApplyOriginal = null;
    }

    /**
     * Turns down one change block (the "x Reject" marker beside it in the editor) and keeps the
     * rest under review; once nothing is left the review closes on the original script. The
     * Chat tab is rebuilt so its Accept/Reject row follows.
     */
    private void rejectHunk(int index) {
        if (!isReviewing() || index < 0 || index >= reviewDiff.hunks().size()) {
            return;
        }
        LineDiff remaining = reviewDiff.rejectHunk(index);
        if (!remaining.hasChanges()) {
            endReview(reviewOriginalText);
            rebuildWidgets();
            return;
        }
        reviewDiff = remaining;
        editor.setValue(remaining.mergedText());
        applyReviewDecorations();
    }

    /**
     * Draws an "x Reject" marker at the right edge of each change block's first line and records
     * its rectangle for {@link #mouseClicked} - the per-block control Cursor puts beside each
     * hunk. Scrolls with the text; blocks scrolled out of the editor get no marker.
     */
    private void renderReviewHunkMarkers(GuiGraphics guiGraphics) {
        reviewHunkMarkerRects.clear();
        if (!isReviewing()) {
            return;
        }
        String label = Component.translatable("gui.micradrone.ide_screen.reject_hunk").getString();
        int markerWidth = this.font.width(label) + 2 * HUNK_MARKER_PADDING;
        int right = editor.getX() + editor.getWidth() - HUNK_MARKER_INSET;
        int firstTextY = editorTop + editor.gutterTopPadding();
        int scroll = (int) editor.gutterScroll();
        List<LineDiff.Hunk> hunks = reviewDiff.hunks();
        for (int i = 0; i < hunks.size(); i++) {
            int y = firstTextY + (hunks.get(i).firstLine() - 1) * DebugEditBox.LINE_HEIGHT - scroll;
            if (y < editorTop || y + DebugEditBox.LINE_HEIGHT > editorTop + editorHeight) {
                reviewHunkMarkerRects.add(null);
                continue;
            }
            int[] rect = {right - markerWidth, y - 1, right, y + DebugEditBox.LINE_HEIGHT};
            reviewHunkMarkerRects.add(rect);
            guiGraphics.fill(rect[0], rect[1], rect[2], rect[3], HUNK_MARKER_BACKGROUND);
            guiGraphics.drawString(this.font, label, rect[0] + HUNK_MARKER_PADDING, y, HUNK_MARKER_TEXT_COLOR, false);
        }
    }

    /** Index of the hunk marker under the mouse, or -1. */
    private int hunkMarkerAt(double mouseX, double mouseY) {
        for (int i = 0; i < reviewHunkMarkerRects.size(); i++) {
            int[] r = reviewHunkMarkerRects.get(i);
            if (r != null && mouseX >= r[0] && mouseX < r[2] && mouseY >= r[1] && mouseY < r[3]) {
                return i;
            }
        }
        return -1;
    }

    private void endReview(String finalText) {
        if (!isReviewing()) {
            return;
        }
        reviewDiff = null;
        reviewOriginalText = null;
        editor.setDiffLines(Set.of(), Set.of());
        editor.setLocked(false);
        editorText = finalText;
        editor.setValue(finalText);
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
            setInitialFocus(); // no rebuild happens here, so the setInitialFocus() hook doesn't fire on its own
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
        if (button == 0 && isReviewing()) {
            int hunk = hunkMarkerAt(mouseX, mouseY);
            if (hunk >= 0) {
                rejectHunk(hunk);
                return true;
            }
        }
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
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        // A click on any button leaves that BUTTON holding the keyboard focus (vanilla's
        // ContainerEventHandler focuses whatever was clicked), after which the arrow keys walk the
        // widget focus ring and typing goes nowhere - the "arrow keys sometimes jump the UI" real-
        // machine report. None of this screen's buttons do anything with keyboard focus, so hand it
        // straight back to the editor, the way a desktop IDE keeps the caret in the text after a
        // toolbar click. Not while the Chat tab is open: there the next thing typed belongs in the
        // chat's own input box, which is not a Button and so keeps its focus untouched.
        if (getFocused() instanceof Button && editor != null && !chatPanel.isOpen()) {
            setFocused(editor);
        }
        return handled;
    }

    /**
     * While renaming (title double-clicked), Enter commits and Escape cancels - both consumed
     * before reaching the rename box itself; everything else (typing, arrows, backspace) still
     * flows through to it via {@code super.keyPressed} + {@link #setFocused}.
     *
     * <p>Otherwise, while the autocomplete popup is showing <em>and the editor holds the
     * keyboard</em>: Up/Down move the selection and Escape dismisses it, all three consumed
     * outright. Tab and Enter accept the highlighted suggestion, but are consumed only when it
     * actually applied - {@link #acceptAutocomplete} refuses a stale one, and the key then falls
     * through to {@code super.keyPressed}, where Enter inserts its newline as usual and Tab reaches
     * {@link DebugEditBox#keyPressed}, which types {@code TAB_AS_SPACES} - it no longer moves the
     * screen's focus (that vanilla fallback only ever ran because the editor used to ignore Tab).
     * Up/Down/Escape/Tab are the same keys vanilla's {@code CommandSuggestions.SuggestionsList}
     * binds; accepting with Enter as well is this editor's own addition. Key codes come from
     * {@link GLFW} rather than raw numbers. Everything else - including all of these once the popup
     * is empty or the editor is unfocused - goes to the focused widget as usual.
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (chatPanel.handleKeyPressed(keyCode)) {
            return true;
        }
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
                    // Leaves the key unconsumed when the suggestion turned out to be stale, so a
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
        chatPanel.tick();
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
        closed = true;
        chatPanel.close();
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
        if (listMode || chatPanel.isOpen()) {
            guiGraphics.fill(listPanelX() - ROW_GAP, 0, this.width, this.height, 0xE0101010);
        }
        renderEditorTitleBar(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        chatPanel.render(guiGraphics);
        Component heading = isReviewing()
                ? Component.translatable("gui.micradrone.ide_screen.reviewing")
                : Component.translatable("gui.micradrone.ide_screen.heading", displayName);
        guiGraphics.drawCenteredString(this.font, heading, this.width / 2, MARGIN,
                isReviewing() ? REVIEW_HEADING_COLOR : 0xFFFFFF);
        renderPointsHud(guiGraphics);
        renderGutter(guiGraphics);
        renderReviewHunkMarkers(guiGraphics);
        refreshAutocomplete();
        renderAutocompletePopup(guiGraphics);
    }

    /**
     * Title-bar strip spanning the editor's width, drawn behind the Play and Step icons so they
     * read as one header row (icons + script name) rather than floating buttons - like a code
     * editor's title bar/toolbar, with room for more controls later. Both icon widgets are
     * rendered afterward by {@code super.render}, painting over this bar's left end so they
     * visually sit on top of it. The script name is skipped while a rename is in progress, since
     * the rename box occupies that same spot.
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
