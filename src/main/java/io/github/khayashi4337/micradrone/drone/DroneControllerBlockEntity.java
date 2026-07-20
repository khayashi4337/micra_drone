package io.github.khayashi4337.micradrone.drone;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.github.khayashi4337.micradrone.drone.net.DroneLogPayload;
import io.github.khayashi4337.micradrone.drone.net.ShopStatePayload;
import io.github.khayashi4337.micradrone.lang.Lexer;
import io.github.khayashi4337.micradrone.lang.Parser;
import io.github.khayashi4337.micradrone.lang.ast.Stmt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
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
    /** Used when no corner marker is found (see {@link #scanForCornerMarker}). */
    private static final int DEFAULT_WORLD_SIZE = 5;
    private static final int MAX_MARKER_SCAN_DISTANCE = 64;
    /** Natural terrain is rarely perfectly flat, so the marker doesn't have to sit at the exact same Y. */
    private static final int MAX_MARKER_SCAN_Y_TOLERANCE = 4;
    /** Bounds how much log history is kept/sent; older lines are dropped as new ones arrive. */
    private static final int LOG_BUFFER_CAPACITY = 100;
    /**
     * How often the claimed plot's crops get an extra bonemeal-style growth jump (see
     * {@link LiveFarmBlockAccess#boostGrowth()}), making the plot grow noticeably faster than
     * vanilla farmland. 100 ticks = 5 seconds.
     */
    private static final int GROWTH_BOOST_INTERVAL_TICKS = 100;

    private final PacedActionQueue pacedActionQueue = new PacedActionQueue();
    private final Deque<String> logBuffer = new ArrayDeque<>();
    /** The player who last clicked Run/Stop/opened the screen; log updates are pushed to them. */
    private UUID viewingPlayerUuid;

    // Written only on the main thread (paced action apply, or scanForCornerMarker); read from the
    // script worker thread too.
    private volatile int gridX = 0;
    private volatile int gridY = 0;
    private volatile int worldSize = DEFAULT_WORLD_SIZE;
    private volatile int dirX = 1;
    private volatile int dirZ = 1;
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
    // Human-readable label a player can set - coordinates alone are hard to tell apart. The script
    // folder on disk is named after this (falling back to coordinates when blank) and gets renamed
    // in place when it changes - see setAlias and ScriptFileStore#folderName(String, int, int, int).
    private volatile String alias = "";
    private volatile String selectedScript = ScriptFileStore.DEFAULT_SCRIPT_NAME;
    // Refreshed from disk in sendLogSnapshotTo (screen open); reused as-is by every other push so
    // routine log/points updates don't hit the filesystem. File name -> description (see
    // ScriptFileStore#describeScript).
    private volatile Map<String, String> scriptDescriptions =
            Map.of(ScriptFileStore.DEFAULT_SCRIPT_NAME, ScriptFileStore.DEFAULT_SCRIPT_NAME);

    private DroneScriptRunner scriptRunner;
    /** The visible {@link DroneEntity} tracked by UUID (entities aren't safe to hold direct references to across reloads). */
    private UUID droneEntityUuid;

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
        double y = getBlockPos().getY() + 1.0;
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
    public long getPoints(String crop) {
        return pointsByCrop.getOrDefault(crop, 0L);
    }

    @Override
    public void addPoints(String crop, long delta) {
        long newTotal = pointsByCrop.merge(crop, delta, Long::sum);
        setChanged();
        pushLogSnapshot();
        resolveViewingPlayer().ifPresent(player -> MicraDroneAdvancements.checkHarvestMilestones(player, crop, newTotal));
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
        sendShopStateTo(requester);
    }

    public void sendShopStateTo(ServerPlayer requester) {
        PacketDistributor.sendToPlayer(requester, new ShopStatePayload(getBlockPos(), Set.copyOf(unlockedCrops), pointsByCrop()));
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
                MAX_MARKER_SCAN_DISTANCE, MAX_MARKER_SCAN_Y_TOLERANCE, DEFAULT_WORLD_SIZE);
        worldSize = bounds.worldSize();
        dirX = bounds.dirX();
        dirZ = bounds.dirZ();
        // Ambient effects like the growth boost must never apply to the size-5-toward-SE guess used
        // when no marker has actually been placed/found - only to a plot the player explicitly marked.
        plotConfirmed = bounds.markerFound();
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
     * Reads {@code scriptName}'s source from this controller's script folder (creating the folder and
     * seeding it with {@link SampleScripts#ALL} if missing) without running it - used to fill a blank
     * {@code ScriptScrollItem} (GitHub issue #1) so a player can carry/hand off a known-good script
     * without typing one by hand first. Empty on any I/O error (logged); the caller is a network
     * payload handler with no player-facing error channel besides the log.
     */
    public Optional<String> loadScriptSource(String scriptName) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return Optional.empty();
        }
        try {
            return Optional.of(ScriptFileStore.load(controllerScriptFolder(serverLevel).resolve(scriptName)));
        } catch (IOException e) {
            MicraDrone.LOGGER.error("could not read script '{}' for scroll fill at {}", scriptName, getBlockPos(), e);
            return Optional.empty();
        }
    }

    /**
     * Saves {@code scriptSource} (a written {@code ScriptScrollItem}'s joined pages - GitHub issue #1)
     * as this controller's {@link ScriptScrollContent#SCROLL_SCRIPT_NAME} script and runs it
     * immediately, exactly as if the player had picked and run a saved script through
     * {@code DroneScreen}.
     */
    public void applyScroll(ServerPlayer requester, String scriptSource) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        try {
            Files.writeString(controllerScriptFolder(serverLevel).resolve(ScriptScrollContent.SCROLL_SCRIPT_NAME), scriptSource);
        } catch (IOException e) {
            appendLog("[error] could not save scroll content: " + e.getMessage());
            return;
        }
        startScript(requester, ScriptScrollContent.SCROLL_SCRIPT_NAME);
    }

    /**
     * Loads {@code scriptName} from this controller's script folder (creating the folder and seeding
     * it with {@link SampleScripts#ALL} if missing) and runs it. No-op (besides a log line) if a
     * script is already running.
     */
    public void startScript(ServerPlayer requester, String scriptName) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        viewingPlayerUuid = requester.getUUID();
        if (scriptRunner != null && scriptRunner.getState() == DroneScriptRunner.State.RUNNING) {
            appendLog("[run] a script is already running");
            return;
        }
        logBuffer.clear();
        selectedScript = scriptName;
        setChanged();

        String source;
        try {
            Path folder = controllerScriptFolder(serverLevel);
            source = ScriptFileStore.load(folder.resolve(scriptName));
        } catch (IOException e) {
            appendLog("[error] could not read script '" + scriptName + "': " + e.getMessage());
            return;
        }

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
        scriptRunner = new DroneScriptRunner(api, this::appendLog);
        appendLog("[run] running " + scriptName);
        scriptRunner.start(program);
    }

    /** Requests the running script (if any) to stop. Safe to call even when nothing is running. */
    public void stopScript(ServerPlayer requester) {
        viewingPlayerUuid = requester.getUUID();
        if (scriptRunner == null) {
            return;
        }
        scriptRunner.stop();
        appendLog("[stop] stop requested");
    }

    /**
     * DroneScreen's alias field: a human-readable label, so e.g. "North Farm" beats a bare
     * coordinate. Also renames the script folder on disk to match (see
     * ScriptFileStore#renameControllerFolder) so the saved scripts follow the new name instead of
     * being left behind under the old one.
     */
    public void setAlias(String alias) {
        if (level instanceof ServerLevel serverLevel && !alias.equals(this.alias)) {
            BlockPos pos = getBlockPos();
            try {
                ScriptFileStore.renameControllerFolder(scriptsDir(serverLevel), this.alias, alias, pos.getX(), pos.getY(), pos.getZ());
            } catch (IOException e) {
                MicraDrone.LOGGER.error("could not rename script folder for {}", pos, e);
            }
        }
        this.alias = alias;
        setChanged();
        pushLogSnapshot();
    }

    /**
     * Sent when a {@code DroneScreen} opens, so it immediately shows log/points/alias history and an
     * up-to-date script list instead of starting blank.
     */
    public void sendLogSnapshotTo(ServerPlayer requester) {
        viewingPlayerUuid = requester.getUUID();
        if (level instanceof ServerLevel serverLevel) {
            refreshAvailableScripts(serverLevel);
        }
        pushLogSnapshotTo(requester);
    }

    private void refreshAvailableScripts(ServerLevel level) {
        try {
            Path folder = controllerScriptFolder(level);
            Map<String, String> described = ScriptFileStore.listScriptsWithDescriptions(folder);
            if (!described.isEmpty()) {
                scriptDescriptions = described;
                if (!described.containsKey(selectedScript)) {
                    selectedScript = described.keySet().iterator().next();
                }
            }
        } catch (IOException e) {
            MicraDrone.LOGGER.error("could not list scripts for {}", getBlockPos(), e);
        }
    }

    private Path controllerScriptFolder(ServerLevel level) throws IOException {
        BlockPos pos = getBlockPos();
        return ScriptFileStore.ensureControllerFolder(scriptsDir(level), alias, pos.getX(), pos.getY(), pos.getZ());
    }

    private static Path scriptsDir(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve("micradrone").resolve("scripts");
    }

    private void appendLog(String line) {
        MicraDrone.LOGGER.info("[drone {}] {}", getBlockPos(), line);
        logBuffer.addLast(line);
        while (logBuffer.size() > LOG_BUFFER_CAPACITY) {
            logBuffer.removeFirst();
        }
        pushLogSnapshot();
    }

    private void pushLogSnapshot() {
        resolveViewingPlayer().ifPresent(this::pushLogSnapshotTo);
    }

    /** The player who last clicked Run/Stop/opened the screen, if they're still online - see {@link #viewingPlayerUuid}. */
    private Optional<ServerPlayer> resolveViewingPlayer() {
        if (!(level instanceof ServerLevel serverLevel) || viewingPlayerUuid == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(serverLevel.getServer().getPlayerList().getPlayer(viewingPlayerUuid));
    }

    private void pushLogSnapshotTo(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player,
                new DroneLogPayload(getBlockPos(), List.copyOf(logBuffer), pointsByCrop(), scriptDescriptions, selectedScript, alias));
    }

    /** Registered as this block's {@link net.minecraft.world.level.block.entity.BlockEntityTicker}; server-side only. */
    public static void serverTick(Level level, BlockPos pos, BlockState state, DroneControllerBlockEntity be) {
        int tick = level.getServer().getTickCount();
        be.pacedActionQueue.tick(tick);
        if (be.plotConfirmed && tick % GROWTH_BOOST_INTERVAL_TICKS == 0) {
            new LiveFarmBlockAccess(level, pos, be).boostGrowth();
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
        selectedScript = tag.contains("SelectedScript") ? tag.getString("SelectedScript") : ScriptFileStore.DEFAULT_SCRIPT_NAME;
        unlockedCrops.clear();
        unlockedCrops.add("wheat");
        ListTag unlockedTag = tag.getList("UnlockedCrops", Tag.TAG_STRING);
        for (Tag t : unlockedTag) {
            unlockedCrops.add(t.getAsString());
        }
        droneEntityUuid = tag.hasUUID("DroneEntityUuid") ? tag.getUUID("DroneEntityUuid") : null;
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
    }
}
