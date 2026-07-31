package io.github.khayashi4337.micradrone.drone;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.github.khayashi4337.micradrone.drone.net.DebugCommandPayload;
import io.github.khayashi4337.micradrone.drone.net.DebugStatePayload;
import io.github.khayashi4337.micradrone.drone.net.DroneLogPayload;
import io.github.khayashi4337.micradrone.drone.net.ScriptEntry;
import io.github.khayashi4337.micradrone.drone.net.ScriptSourcePayload;
import io.github.khayashi4337.micradrone.drone.net.ShopStatePayload;
import io.github.khayashi4337.micradrone.lang.DebugController;
import io.github.khayashi4337.micradrone.lang.Lexer;
import io.github.khayashi4337.micradrone.lang.Parser;
import io.github.khayashi4337.micradrone.lang.ast.Stmt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Holds the drone's grid position for the farm plot claimed by this controller. Plot size/direction
 * is decided by an optional paired {@link MicraDrone#CORNER_MARKER_BLOCK}: it must sit on one of the
 * 4 world-space diagonals from this block (dx == dz in absolute value) so the plot is always square,
 * which both matches the original game's single-side-length farm size and keeps the search simple -
 * only 4 rays need scanning instead of a general-area search.
 */
public class DroneControllerBlockEntity extends BlockEntity implements DroneGridState {
    // Public (not private): the IDE screen's live plot view re-runs the same corner-marker scan
    // against the client-side level and must use identical parameters to resolve the same plot.
    /** Used when no corner marker is found (see {@link #scanForCornerMarker}). */
    public static final int DEFAULT_WORLD_SIZE = 5;
    public static final int MAX_MARKER_SCAN_DISTANCE = 64;
    /** Natural terrain is rarely perfectly flat, so the marker doesn't have to sit at the exact same Y. */
    public static final int MAX_MARKER_SCAN_Y_TOLERANCE = 4;
    /** Bounds how much log history is kept/sent; older lines are dropped as new ones arrive. */
    private static final int LOG_BUFFER_CAPACITY = 100;
    /**
     * How often the claimed plot's crops get an extra bonemeal-style growth jump (see
     * {@link LiveFarmBlockAccess#boostGrowth()}), making the plot grow noticeably faster than
     * vanilla farmland. 100 ticks = 5 seconds.
     */
    private static final int GROWTH_BOOST_INTERVAL_TICKS = 100;

    private final PacedActionQueue pacedActionQueue = new PacedActionQueue();
    /**
     * Guarded by its own monitor: {@link #appendLog} runs on the script's worker thread (print() is
     * the one DroneApi call that never hops to the main thread - see LiveDroneApi#print), while
     * clearing and snapshotting happen on the main thread.
     */
    private final Deque<String> logBuffer = new ArrayDeque<>();
    /**
     * Set whenever the log/points/script list changed, cleared by {@link #maybePushLogSnapshot} on
     * the next tick. The push itself has to happen on the main thread (it sends packets), and
     * coalescing per tick also means a chatty script costs one packet per tick per viewer instead of
     * one per print() per viewer - which matters far more with several players watching.
     */
    private volatile boolean logDirty = false;
    /**
     * Everyone currently looking at this controller through a screen (Scripts/IDE/Shop), not just
     * one player: on a shared server several people routinely watch the same farm, and pushing to
     * only the most recent one silently froze everybody else's screen. Kept as UUIDs because
     * ServerPlayer instances don't survive a respawn/dimension change; entries are pruned as soon as
     * a player goes offline (see {@link #forEachViewer}).
     */
    private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();
    /**
     * The player this controller's work is credited to: whoever last started a run. Deliberately
     * separate from {@link #viewers} - harvest advancements belong to the
     * player who set the drone going, not to a bystander who happened to open the screen while it
     * ran. Persisted so the redstone path (which has no requester at all) still credits the player
     * who set the controller up, even across a server restart.
     */
    private volatile UUID ownerUuid;

    // Written only on the main thread (paced action apply, or scanForCornerMarker); read from the
    // script worker thread too.
    private volatile int gridX = 0;
    private volatile int gridY = 0;
    private volatile int worldSize = DEFAULT_WORLD_SIZE;
    private volatile int dirX = 1;
    private volatile int dirZ = 1;
    private volatile int groundYOffset = 0;
    // True only once scanForCornerMarker has actually found a paired corner marker - see its use in
    // serverTick, which must not ambient-boost growth in the size-5-toward-SE guess used otherwise.
    private volatile boolean plotConfirmed = false;
    // Belongs to this controller, not the plot's geometry: survives corner-marker re-scans on purpose.
    // Keyed by crop name (e.g. "wheat"); written on the main thread, read from the network/GUI push
    // path too, hence a concurrent map rather than a plain HashMap.
    private final Map<String, Long> pointsByCrop = new ConcurrentHashMap<>();
    // "wheat" is always in here (every plot starts able to plant it); others are bought in the shop
    // (see purchaseUnlock). Written on the main thread only (purchaseUnlock runs from the network
    // handler, which is main-thread per PayloadRegistrar's default).
    private final Set<String> unlockedCrops = ConcurrentHashMap.newKeySet();
    // Human-readable label - coordinates alone are hard to tell apart. Set by renaming the
    // controller ITEM in a vanilla anvil before placing it (the CUSTOM_NAME component flows in via
    // applyImplicitComponents, the same route vanilla chests use); the script folder on disk is
    // named after it, falling back to coordinates when blank - see ScriptFileStore#folderName.
    private volatile String alias = "";
    // Empty until a script is actually picked (or saved/run) at least once - see ScriptId.isValidId,
    // which rejects "" so an early redstone signal or right-click just logs/no-ops instead of crashing.
    private volatile String selectedScript = "";
    // Refreshed from the library containers in sendLogSnapshotTo (screen open); reused as-is by
    // every other push so routine log/points updates don't re-scan anything. On-disk .mdrone files
    // are no longer listed (GUI reduction, issue #7) - scripts live in items now; the file store
    // remains as internal plumbing.
    private volatile List<ScriptEntry> availableScripts = List.of();

    private DroneScriptRunner scriptRunner;
    /** The visible {@link DroneEntity} tracked by UUID (entities aren't safe to hold direct references to across reloads). */
    private UUID droneEntityUuid;

    // IDE debugger (issue #6). Breakpoints are per-controller and session-only (deliberately not
    // saved to NBT); the controller is recreated for every run with the current set applied.
    private volatile Set<Integer> breakpoints = Set.of();
    private volatile DebugController debugController;
    /** The debug snapshot last pushed to the viewing player - see {@link #maybePushDebugState}. */
    private DebugStatePayload lastPushedDebugState;

    public DroneControllerBlockEntity(BlockPos pos, BlockState state) {
        super(MicraDrone.DRONE_CONTROLLER_BLOCK_ENTITY.get(), pos, state);
        unlockedCrops.add("wheat");
    }

    public PacedActionQueue getPacedActionQueue() {
        return pacedActionQueue;
    }

    @Override
    public int gridX() {
        return gridX;
    }

    @Override
    public int gridY() {
        return gridY;
    }

    @Override
    public void setGridPos(int x, int y) {
        this.gridX = x;
        this.gridY = y;
        setChanged();
        if (level instanceof ServerLevel serverLevel) {
            syncDronePosition(serverLevel);
        }
    }

    /** Moves the visible {@link DroneEntity} to match the current grid position, spawning it if needed. */
    private void syncDronePosition(ServerLevel level) {
        int[] offset = PlotGeometry.groundOffset(dirX, dirZ, gridX, gridY);
        double x = getBlockPos().getX() + offset[0] + 0.5;
        double y = getBlockPos().getY() + 1.0 + groundYOffset;
        double z = getBlockPos().getZ() + offset[1] + 0.5;

        DroneEntity drone = resolveDroneEntity(level);
        if (drone == null) {
            drone = MicraDrone.DRONE_ENTITY.get().create(level);
            if (drone == null) {
                return; // shouldn't happen; entity factory misconfigured
            }
            drone.moveTo(x, y, z);
            drone.setPersistenceRequired(); // tied to this controller - must not naturally despawn
            level.addFreshEntity(drone);
            droneEntityUuid = drone.getUUID();
            setChanged();
        } else {
            drone.moveTo(x, y, z);
        }
    }

    private DroneEntity resolveDroneEntity(ServerLevel level) {
        if (droneEntityUuid == null) {
            return null;
        }
        return level.getEntity(droneEntityUuid) instanceof DroneEntity drone ? drone : null;
    }

    /** Removes the visible drone entity, e.g. when this controller block is broken. */
    public void discardDroneEntity() {
        if (level instanceof ServerLevel serverLevel) {
            DroneEntity drone = resolveDroneEntity(serverLevel);
            if (drone != null) {
                drone.discard();
            }
        }
        droneEntityUuid = null;
    }

    @Override
    public int worldSize() {
        return worldSize;
    }

    @Override
    public int dirX() {
        return dirX;
    }

    @Override
    public int dirZ() {
        return dirZ;
    }

    @Override
    public int groundYOffset() {
        return groundYOffset;
    }

    @Override
    public long getPoints(String crop) {
        return pointsByCrop.getOrDefault(crop, 0L);
    }

    @Override
    public void addPoints(String crop, long delta) {
        long newTotal = pointsByCrop.merge(crop, delta, Long::sum);
        setChanged();
        pushLogSnapshot();
        // Credited to the owner, not to whoever has the screen open: on a shared server a bystander
        // opening the Scripts screen mid-run used to collect the harvest advancements instead.
        resolveOwner().ifPresent(player -> MicraDroneAdvancements.checkHarvestMilestones(player, crop, newTotal));
    }

    @Override
    public Map<String, Long> pointsByCrop() {
        return Map.copyOf(pointsByCrop);
    }

    @Override
    public boolean isUnlocked(String crop) {
        return unlockedCrops.contains(crop);
    }

    /**
     * Spends this plot's points on {@code unlockId} (see {@link UnlockShop#CATALOG}) if it exists,
     * isn't already unlocked, and enough points are available - a no-op (besides a chat message)
     * otherwise. Either way, sends the requester a fresh {@link ShopStatePayload} so the Shop screen
     * reflects the outcome immediately.
     */
    public void purchaseUnlock(ServerPlayer requester, String unlockId) {
        Optional<UnlockShop.Unlock> unlock = UnlockShop.find(unlockId);
        if (unlock.isEmpty()) {
            requester.sendSystemMessage(Component.literal("[shop] unknown unlock '" + unlockId + "'"));
        } else if (unlockedCrops.contains(unlockId)) {
            requester.sendSystemMessage(Component.literal("[shop] " + unlockId + " is already unlocked"));
        } else if (!UnlockShop.canAfford(pointsByCrop(), unlock.get().cost())) {
            requester.sendSystemMessage(Component.literal("[shop] not enough points to unlock " + unlockId));
        } else {
            unlock.get().cost().forEach((crop, amount) -> pointsByCrop.merge(crop, -amount, Long::sum));
            unlockedCrops.add(unlockId);
            setChanged();
            requester.sendSystemMessage(Component.literal("[shop] unlocked " + unlockId));
            MicraDroneAdvancements.awardUnlock(requester, unlockId);
        }
        // Broadcast, not just to the buyer: a plot's unlocks and points are shared, so anyone else
        // with the Shop screen open needs to see the balance drop too, or they'll try to buy with
        // points that are already spent.
        sendShopStateTo(requester);
        forEachViewer(viewer -> {
            if (!viewer.getUUID().equals(requester.getUUID())) {
                sendShopStateTo(viewer);
            }
        });
    }

    public void sendShopStateTo(ServerPlayer requester) {
        sendShopStateTo(requester, null);
    }

    /**
     * @param clickedMarkerId if the Shop screen was opened by right-clicking a specific Corner Marker,
     *     that marker's own {@code displayId()} - shown as-is instead of re-deriving "the marker paired
     *     with this controller" (which can be a DIFFERENT marker, e.g. right after placing a second,
     *     not-yet-paired one - 林さんの実機報告: 2つ目のマーカーが1つ目のIDを表示していた). Null when opened via the
     *     controller or the IDE's Shop button, or when re-broadcasting to other viewers after a purchase.
     */
    public void sendShopStateTo(ServerPlayer requester, @Nullable String clickedMarkerId) {
        String plotId = clickedMarkerId != null
                ? clickedMarkerId
                : (level instanceof ServerLevel serverLevel
                        ? CornerMarkerBlockEntity.findDisplayId(serverLevel, getBlockPos())
                        : "");
        PacketDistributor.sendToPlayer(requester,
                new ShopStatePayload(getBlockPos(), Set.copyOf(unlockedCrops), pointsByCrop(), plotId));
    }

    /**
     * Looks for a {@link MicraDrone#CORNER_MARKER_BLOCK} on one of the 4 diagonals from this block
     * (up to {@link #MAX_MARKER_SCAN_DISTANCE} away, within {@link #MAX_MARKER_SCAN_Y_TOLERANCE} of
     * this block's Y level) and, if found, sizes/orients the plot to match. A marker placed off the
     * true X/Z diagonal is simply never found - the plot silently stays square. Falls back to
     * {@link #DEFAULT_WORLD_SIZE} toward south-east when no marker is found.
     */
    private void scanForCornerMarker(ServerLevel level) {
        BlockPos pos = getBlockPos();
        CornerMarkerScan.PlotBounds bounds = CornerMarkerScan.scan(
                (dx, dy, dz) -> level.getBlockState(pos.offset(dx, dy, dz)).is(MicraDrone.CORNER_MARKER_BLOCK.get()),
                (dx, dy, dz) -> isDirtLike(level.getBlockState(pos.offset(dx, dy, dz))),
                MAX_MARKER_SCAN_DISTANCE, MAX_MARKER_SCAN_Y_TOLERANCE, DEFAULT_WORLD_SIZE);
        worldSize = bounds.worldSize();
        dirX = bounds.dirX();
        dirZ = bounds.dirZ();
        groundYOffset = bounds.groundYOffset();
        // Ambient effects like the growth boost must never apply to the size-5-toward-SE guess used
        // when no marker has actually been placed/found - only to a plot the player explicitly marked.
        plotConfirmed = bounds.markerFound();
    }

    /**
     * "Soil" for {@link CornerMarkerScan#groundYOffset}'s placement-style check: vanilla's own
     * {@link BlockTags#DIRT} (dirt/grass/podzol/coarse dirt/mycelium/rooted dirt/moss/mud/muddy
     * mangrove roots - confirmed via the actual tag data) plus farmland, which that tag omits.
     * Public: the IDE screen's client-side plot rescan (see {@link #scanForCornerMarker}'s own
     * javadoc on why that scan is duplicated client-side) needs the exact same rule.
     */
    public static boolean isDirtLike(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(Blocks.FARMLAND);
    }

    /**
     * Reverse lookup for the Shop screen (opened by right-clicking a corner marker, not a
     * controller): given the marker's position, finds the paired controller's BlockEntity, if any is
     * within scan range. Reuses the exact same diagonal-scan algorithm scanForCornerMarker uses, just
     * searching for the opposite block type and starting point.
     */
    public static Optional<DroneControllerBlockEntity> findByCornerMarker(Level level, BlockPos markerPos) {
        Optional<int[]> offset = CornerMarkerScan.findNearestMatch(
                (dx, dy, dz) -> level.getBlockState(markerPos.offset(dx, dy, dz)).is(MicraDrone.DRONE_CONTROLLER_BLOCK.get()),
                MAX_MARKER_SCAN_DISTANCE, MAX_MARKER_SCAN_Y_TOLERANCE);
        if (offset.isEmpty()) {
            return Optional.empty();
        }
        int[] o = offset.get();
        BlockPos controllerPos = markerPos.offset(o[0], o[1], o[2]);
        return level.getBlockEntity(controllerPos) instanceof DroneControllerBlockEntity be
                ? Optional.of(be)
                : Optional.empty();
    }

    /**
     * Reads {@code scriptName}'s source (library scroll, the requester's own inventory scroll, or
     * the controller's script folder) without running it. Empty on any I/O error (logged); the
     * caller is a network payload handler with no player-facing error channel besides the log.
     * {@code requester} may be null (the redstone run path) - an inventory scroll id then can't
     * resolve to anyone's inventory and simply fails, same as any other missing script.
     */
    public Optional<String> loadScriptSource(@Nullable ServerPlayer requester, String scriptName) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return Optional.empty();
        }
        if (ScriptId.isScrollId(scriptName)) {
            return ScriptChestLibrary.resolveScrollSource(serverLevel, getBlockPos(), scriptName);
        }
        if (ScriptId.isInventoryScrollId(scriptName)) {
            return requester != null
                    ? ScriptChestLibrary.resolveInventoryScrollSource(requester, scriptName)
                    : Optional.empty();
        }
        try {
            return Optional.of(ScriptFileStore.load(controllerScriptFolder(serverLevel).resolve(scriptName)));
        } catch (IOException e) {
            MicraDrone.LOGGER.error("could not read script '{}' for scroll fill at {}", scriptName, getBlockPos(), e);
            return Optional.empty();
        }
    }

    /**
     * Sends {@code scriptName}'s source to {@code requester} for editing in {@code IdeScreen}
     * (issue #6). The name arrives over the network, so it's validated before touching the
     * filesystem; failures (bad name, unreadable file) are reported to the player's chat rather
     * than silently doing nothing.
     */
    public void sendScriptSource(ServerPlayer requester, String scriptName) {
        addViewer(requester);
        if (!ScriptId.isValidId(scriptName)) {
            requester.sendSystemMessage(Component.literal("[ide] invalid script id '" + scriptName + "'"));
            return;
        }
        loadScriptSource(requester, scriptName).ifPresentOrElse(
                source -> PacketDistributor.sendToPlayer(requester, new ScriptSourcePayload(getBlockPos(), scriptName, source)),
                () -> requester.sendSystemMessage(Component.literal("[ide] could not read '" + scriptName + "'")));
        pushDebugStateTo(requester); // a (re)opened IDE learns the server-held breakpoints right away
    }

    /** Longest script {@link #saveScript} accepts - keeps the payload comfortably inside STRING_UTF8's 32767-byte cap even for multibyte text. */
    public static final int MAX_SCRIPT_CHARS = 10000;

    /**
     * Saves {@code source} as {@code scriptName} in this controller's script folder ({@code
     * IdeScreen}'s Save button, issue #6), then refreshes the script list so the IDE's own list
     * reflects the edit immediately. Validates the client-supplied name and length;
     * every outcome, success included, is reported to the player's chat.
     */
    public void saveScript(ServerPlayer requester, String scriptName, String source) {
        addViewer(requester);
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!ScriptId.isValidId(scriptName)) {
            requester.sendSystemMessage(Component.literal("[ide] invalid script id '" + scriptName + "'"));
            return;
        }
        if (source.length() > MAX_SCRIPT_CHARS) {
            requester.sendSystemMessage(Component.literal("[ide] script too long (" + source.length() + " > " + MAX_SCRIPT_CHARS + " chars)"));
            return;
        }
        if (ScriptId.isScrollId(scriptName)) {
            // A chest scroll: write the edit back into the scroll item itself.
            if (!ScriptChestLibrary.saveScrollSource(serverLevel, getBlockPos(), scriptName, source)) {
                requester.sendSystemMessage(Component.literal(
                        "[ide] scroll " + scriptName + " is no longer in a library chest - nothing saved"));
                return;
            }
        } else if (ScriptId.isInventoryScrollId(scriptName)) {
            // An inventory scroll: write the edit back into the requester's own scroll item.
            if (!ScriptChestLibrary.saveInventoryScrollSource(requester, scriptName, source)) {
                requester.sendSystemMessage(Component.literal(
                        "[ide] scroll " + scriptName + " is no longer in your inventory - nothing saved"));
                return;
            }
        } else {
            try {
                Files.writeString(controllerScriptFolder(serverLevel).resolve(scriptName), source);
            } catch (IOException e) {
                requester.sendSystemMessage(Component.literal("[ide] could not save '" + scriptName + "': " + e.getMessage()));
                return;
            }
        }
        selectedScript = scriptName;
        setChanged();
        refreshAvailableScripts(serverLevel);
        pushLogSnapshotTo(requester);
        requester.sendSystemMessage(Component.literal("[ide] saved " + scriptName));
    }

    private final RedstoneEdge redstoneEdge = new RedstoneEdge();

    /**
     * The GUI-free run path (issue #7, generalized in the GUI-reduction follow-up): a rising
     * redstone edge runs whichever script is currently selected (see {@link #selectedScript} -
     * chosen from the IDE's list, same as clicking Run there), a falling edge stops the running
     * script. Called from DroneControllerBlock#neighborChanged on the server.
     */
    public void onNeighborSignalChange(boolean powered) {
        switch (redstoneEdge.update(powered)) {
            case RISING -> {
                appendLog("[redstone] signal on");
                startScript(null, selectedScript);
            }
            case FALLING -> {
                if (scriptRunner != null) {
                    scriptRunner.stop();
                    appendLog("[redstone] signal off - stop requested");
                }
            }
            case NONE -> { }
        }
    }

    /**
     * Resolves {@code scriptName} (chest scroll or file) and runs it. {@code requester} may be null
     * for the redstone path - the run then belongs to whoever last claimed the controller (see
     * {@link #ownerUuid}), and its log goes to every open screen either way.
     *
     * <p>State-transition design (林さんのフィードバック: ステップ実行モード中にRunを押しても
     * 反応するように): while a script is alive, {@link DroneScriptRunner.State} stays
     * {@code RUNNING} whether or not the debugger has it paused - pause is a sub-state tracked only
     * by {@link DebugController#isPaused()}. So the SAME Run control means "continue" when paused
     * mid-debug, and only refuses when a script is genuinely running unpaused (an actual conflict -
     * two script runs would fight over the drone).
     */
    public void startScript(ServerPlayer requester, String scriptName) {
        if (requester != null) {
            addViewer(requester); // even a refused/resumed Run should show its log line
        }
        if (scriptRunner != null && scriptRunner.getState() == DroneScriptRunner.State.RUNNING) {
            DebugController debug = debugController;
            if (debug != null && debug.isPaused()) {
                debug.resume();
                return;
            }
            appendLog("[run] a script is already running");
            return;
        }
        startFreshRun(requester, scriptName, false);
    }

    /**
     * Shared body of {@link #startScript} and the Step-from-cold path in {@link #debugCommand}:
     * resolves, parses, and launches {@code scriptName}. {@code startPaused} arms the debugger to
     * pause before the very first statement instead of running to completion - see
     * {@link DebugController#requestPause} (called before the worker thread starts, so the first
     * {@code onStatement} call already finds a pause pending).
     */
    private void startFreshRun(ServerPlayer requester, String scriptName, boolean startPaused) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!ScriptId.isValidId(scriptName)) {
            appendLog("[error] invalid script id '" + scriptName + "'");
            return;
        }
        clearLog();
        selectedScript = scriptName;
        // Actually starting a run is what claims this controller, so a second player's refused Run
        // above can't take the harvests of the run already going. The redstone path passes no
        // requester and deliberately keeps whoever claimed it last.
        if (requester != null) {
            ownerUuid = requester.getUUID();
        }
        setChanged();

        Optional<String> loaded = loadScriptSource(requester, scriptName);
        if (loaded.isEmpty()) {
            appendLog("[error] could not read script '" + scriptName + "' - missing scroll or file; reopen the screen to refresh the list");
            return;
        }
        String source = loaded.get();

        List<Stmt> program;
        try {
            program = new Parser(new Lexer(source).scan()).parseProgram();
        } catch (RuntimeException e) {
            appendLog("[error] " + e.getMessage());
            return;
        }

        scanForCornerMarker(serverLevel);
        setGridPos(0, 0); // every run starts the drone back at the plot's origin cell
        MainThreadGateway gateway = new ServerMainThreadGateway(serverLevel.getServer());
        FarmBlockAccess farm = new LiveFarmBlockAccess(serverLevel, getBlockPos(), this);
        LiveDroneApi api = new LiveDroneApi(gateway, pacedActionQueue, this, farm, this::appendLog);
        DebugController debug = new DebugController();
        debug.setBreakpoints(breakpoints);
        if (startPaused) {
            debug.requestPause();
        }
        debugController = debug;
        scriptRunner = new DroneScriptRunner(api, this::appendLog, debug);
        appendLog(startPaused ? "[run] stepping " + scriptName : "[run] running " + scriptName);
        scriptRunner.start(program);
    }

    /**
     * Marks {@code scriptId} as selected without running or saving anything (the IDE's script
     * list, GUI reduction follow-up) - a redstone signal, or right-clicking the controller with an
     * empty hand, subsequently acts on whatever was selected last. Silently ignored (not logged;
     * this is a routine picker interaction, not a script action) if {@code scriptId} isn't a shape
     * the server recognizes.
     */
    public void selectScript(ServerPlayer requester, String scriptId) {
        addViewer(requester);
        if (!ScriptId.isValidId(scriptId)) {
            return;
        }
        selectedScript = scriptId;
        setChanged();
        pushLogSnapshotTo(requester);
    }

    /** Requests the running script (if any) to stop. Safe to call even when nothing is running. */
    public void stopScript(ServerPlayer requester) {
        addViewer(requester);
        if (scriptRunner == null) {
            return;
        }
        scriptRunner.stop();
        appendLog("[stop] stop requested");
    }

    /**
     * Replaces this controller's debugger breakpoint set (IDE gutter clicks, issue #6). Applies to
     * the running script immediately and to every later run; echoed straight back so the IDE shows
     * the authoritative set.
     */
    public void setBreakpoints(ServerPlayer requester, Set<Integer> lines) {
        addViewer(requester);
        breakpoints = Set.copyOf(lines);
        DebugController debug = debugController;
        if (debug != null) {
            debug.setBreakpoints(breakpoints);
        }
        pushDebugStateTo(requester);
    }

    /**
     * One debugger action (see DebugCommandPayload.COMMAND_*). Pause/Resume/Step Out need a live
     * run to act on and are no-ops otherwise, but Step is special-cased: pressed with nothing
     * running, it bootstraps a fresh run of {@link #selectedScript} pre-armed to pause before its
     * first statement (see {@link #startFreshRun}'s {@code startPaused}) instead of doing nothing -
     * 林さんのフィードバック: 停止中にステップ実行を押しても反応するように(直感的に「最初の一歩」
     * が動く挙動).
     */
    public void debugCommand(ServerPlayer requester, int command) {
        addViewer(requester);
        boolean running = scriptRunner != null && scriptRunner.getState() == DroneScriptRunner.State.RUNNING;
        if (!running) {
            if (command == DebugCommandPayload.COMMAND_STEP) {
                startFreshRun(requester, selectedScript, true);
            }
            return;
        }
        DebugController debug = debugController;
        switch (command) {
            case DebugCommandPayload.COMMAND_PAUSE -> debug.requestPause();
            case DebugCommandPayload.COMMAND_RESUME -> debug.resume();
            case DebugCommandPayload.COMMAND_STEP -> debug.step();
            case DebugCommandPayload.COMMAND_STEP_OUT -> debug.stepOut();
            default -> { }
        }
    }

    private DebugStatePayload currentDebugState() {
        DroneScriptRunner runner = scriptRunner;
        DebugController debug = debugController;
        boolean running = runner != null && debug != null && runner.getState() == DroneScriptRunner.State.RUNNING;
        int state = !running ? DebugStatePayload.STATE_IDLE
                : debug.isPaused() ? DebugStatePayload.STATE_PAUSED : DebugStatePayload.STATE_RUNNING;
        int line = running ? debug.currentLine() : 0;
        return new DebugStatePayload(getBlockPos(), state, line, breakpoints.stream().sorted().toList());
    }

    /** Unconditional push, e.g. when the IDE opens - the client learns the server-held breakpoints. */
    public void pushDebugStateTo(ServerPlayer player) {
        DebugStatePayload state = currentDebugState();
        lastPushedDebugState = state;
        PacketDistributor.sendToPlayer(player, state);
    }

    /**
     * Called every server tick: pushes the debug snapshot to every viewer only when it changed, so
     * line tracking during a run costs at most one tiny packet per tick per viewer. The snapshot is
     * the same for everyone, so a single "did it change" latch covers them all.
     */
    private void maybePushDebugState() {
        DebugStatePayload state = currentDebugState();
        if (state.equals(lastPushedDebugState)) {
            return;
        }
        lastPushedDebugState = state;
        forEachViewer(player -> PacketDistributor.sendToPlayer(player, state));
    }

    /**
     * The anvil-rename alias route (GUI reduction, issue #7): a controller ITEM renamed in an
     * anvil carries a CUSTOM_NAME component, which lands here when the block is placed - the exact
     * mechanism vanilla chests use for their names. Replaces the old DroneScreen alias field.
     */
    @Override
    protected void applyImplicitComponents(BlockEntity.DataComponentInput componentInput) {
        super.applyImplicitComponents(componentInput);
        Component name = componentInput.get(DataComponents.CUSTOM_NAME);
        if (name != null) {
            alias = name.getString();
        }
    }

    /** The reverse of {@link #applyImplicitComponents}, for if this block ever drops as a named item. */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (!alias.isEmpty()) {
            components.set(DataComponents.CUSTOM_NAME, Component.literal(alias));
        }
    }

    /**
     * Sent when the IDE's list mode opens, so it immediately shows log/points/alias history and an
     * up-to-date script list instead of starting blank.
     */
    public void sendLogSnapshotTo(ServerPlayer requester) {
        addViewer(requester);
        if (level instanceof ServerLevel serverLevel) {
            refreshAvailableScripts(serverLevel);
        }
        pushLogSnapshotTo(requester);
    }

    private void refreshAvailableScripts(ServerLevel level) {
        List<ScriptEntry> entries = new ArrayList<>(ScriptChestLibrary.listScrolls(level, getBlockPos()));
        availableScripts = List.copyOf(entries);
        if (!entries.isEmpty() && entries.stream().noneMatch(entry -> entry.id().equals(selectedScript))) {
            selectedScript = entries.get(0).id();
        }
    }

    private Path controllerScriptFolder(ServerLevel level) throws IOException {
        BlockPos pos = getBlockPos();
        return ScriptFileStore.ensureControllerFolder(scriptsDir(level), alias, pos.getX(), pos.getY(), pos.getZ());
    }

    private static Path scriptsDir(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve("micradrone").resolve("scripts");
    }

    /** Safe to call from the script worker thread - see {@link #logBuffer} and {@link #logDirty}. */
    private void appendLog(String line) {
        MicraDrone.LOGGER.info("[drone {}] {}", getBlockPos(), line);
        synchronized (logBuffer) {
            logBuffer.addLast(line);
            while (logBuffer.size() > LOG_BUFFER_CAPACITY) {
                logBuffer.removeFirst();
            }
        }
        pushLogSnapshot();
    }

    private void clearLog() {
        synchronized (logBuffer) {
            logBuffer.clear();
        }
        pushLogSnapshot();
    }

    /** Marks the snapshot stale; the actual send happens on the next tick - see {@link #logDirty}. */
    private void pushLogSnapshot() {
        logDirty = true;
    }

    private void maybePushLogSnapshot() {
        if (!logDirty) {
            return;
        }
        // Cleared before sending, so a print() landing mid-push re-marks the snapshot stale and gets
        // picked up next tick rather than being swallowed by this one.
        logDirty = false;
        forEachViewer(this::pushLogSnapshotTo);
    }

    /** Registers {@code player} as a viewer of this controller - see {@link #viewers}. */
    public void addViewer(ServerPlayer player) {
        viewers.add(player.getUUID());
    }

    /** Drops {@code player} again, on their screen closing (see StopViewingPayload). */
    public void removeViewer(ServerPlayer player) {
        viewers.remove(player.getUUID());
    }

    /**
     * Runs {@code action} for every viewer still online, dropping the ones who aren't - a player who
     * disconnected with the screen open never sends a close, so this prune is what stops their UUID
     * lingering forever. Main thread only (its callers send packets).
     */
    private void forEachViewer(Consumer<ServerPlayer> action) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        PlayerList players = serverLevel.getServer().getPlayerList();
        Iterator<UUID> stale = viewers.iterator();
        while (stale.hasNext()) {
            ServerPlayer player = players.getPlayer(stale.next());
            if (player == null) {
                stale.remove();
            } else {
                action.accept(player);
            }
        }
    }

    /** The player this controller's work is credited to, if they're online - see {@link #ownerUuid}. */
    private Optional<ServerPlayer> resolveOwner() {
        if (!(level instanceof ServerLevel serverLevel) || ownerUuid == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(serverLevel.getServer().getPlayerList().getPlayer(ownerUuid));
    }

    /**
     * The library's cached container scripts, PLUS {@code player}'s own inventory scrolls scanned
     * fresh right now (林さんの要望) - inventory contents are per-player, so unlike the cached
     * container list this can't be precomputed once and reused for every viewer; scanning 36 slots
     * with no world access is cheap enough to just do on every push.
     */
    private List<ScriptEntry> availableScriptsFor(ServerPlayer player) {
        List<ScriptEntry> combined = new ArrayList<>(availableScripts);
        combined.addAll(ScriptChestLibrary.listInventoryScrolls(player));
        return combined;
    }

    private void pushLogSnapshotTo(ServerPlayer player) {
        List<String> lines;
        synchronized (logBuffer) {
            lines = List.copyOf(logBuffer);
        }
        PacketDistributor.sendToPlayer(player,
                new DroneLogPayload(getBlockPos(), lines, pointsByCrop(), availableScriptsFor(player), selectedScript, alias));
    }

    /** Registered as this block's {@link net.minecraft.world.level.block.entity.BlockEntityTicker}; server-side only. */
    public static void serverTick(Level level, BlockPos pos, BlockState state, DroneControllerBlockEntity be) {
        int tick = level.getServer().getTickCount();
        be.pacedActionQueue.tick(tick);
        be.maybePushDebugState();
        be.maybePushLogSnapshot();
        be.syncActiveBlockState(level, pos, state);
        if (be.plotConfirmed && tick % GROWTH_BOOST_INTERVAL_TICKS == 0) {
            new LiveFarmBlockAccess(level, pos, be).boostGrowth();
        }
    }

    /**
     * Docked/active texture toggle (see {@link DroneControllerBlock#ACTIVE}) - written only when it
     * actually changes, same "diff before you write" discipline as {@link #maybePushDebugState},
     * so an idle controller costs nothing beyond the state comparison every tick.
     */
    private void syncActiveBlockState(Level level, BlockPos pos, BlockState state) {
        boolean running = scriptRunner != null && scriptRunner.getState() == DroneScriptRunner.State.RUNNING;
        if (state.getValue(DroneControllerBlock.ACTIVE) != running) {
            level.setBlock(pos, state.setValue(DroneControllerBlock.ACTIVE, running), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        gridX = tag.getInt("GridX");
        gridY = tag.getInt("GridY");
        pointsByCrop.clear();
        CompoundTag pointsTag = tag.getCompound("PointsByCrop");
        for (String crop : pointsTag.getAllKeys()) {
            pointsByCrop.put(crop, pointsTag.getLong(crop));
        }
        alias = tag.getString("Alias");
        selectedScript = tag.getString("SelectedScript");
        unlockedCrops.clear();
        unlockedCrops.add("wheat");
        ListTag unlockedTag = tag.getList("UnlockedCrops", Tag.TAG_STRING);
        for (Tag t : unlockedTag) {
            unlockedCrops.add(t.getAsString());
        }
        droneEntityUuid = tag.hasUUID("DroneEntityUuid") ? tag.getUUID("DroneEntityUuid") : null;
        // Absent on controllers saved before owner tracking existed - they simply have no owner
        // until someone next hits Run.
        ownerUuid = tag.hasUUID("OwnerUuid") ? tag.getUUID("OwnerUuid") : null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("GridX", gridX);
        tag.putInt("GridY", gridY);
        CompoundTag pointsTag = new CompoundTag();
        pointsByCrop.forEach(pointsTag::putLong);
        tag.put("PointsByCrop", pointsTag);
        tag.putString("Alias", alias);
        tag.putString("SelectedScript", selectedScript);
        ListTag unlockedTag = new ListTag();
        unlockedCrops.forEach(crop -> unlockedTag.add(StringTag.valueOf(crop)));
        tag.put("UnlockedCrops", unlockedTag);
        if (droneEntityUuid != null) {
            tag.putUUID("DroneEntityUuid", droneEntityUuid);
        }
        if (ownerUuid != null) {
            tag.putUUID("OwnerUuid", ownerUuid);
        }
    }
}
