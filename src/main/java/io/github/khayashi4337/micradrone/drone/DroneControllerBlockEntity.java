package io.github.khayashi4337.micradrone.drone;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.github.khayashi4337.micradrone.drone.net.DroneLogPayload;
import io.github.khayashi4337.micradrone.lang.Lexer;
import io.github.khayashi4337.micradrone.lang.Parser;
import io.github.khayashi4337.micradrone.lang.ast.Stmt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
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
    // Belongs to this controller, not the plot's geometry: survives corner-marker re-scans on purpose.
    private volatile long points = 0;

    private DroneScriptRunner scriptRunner;
    /** The visible {@link DroneEntity} tracked by UUID (entities aren't safe to hold direct references to across reloads). */
    private UUID droneEntityUuid;

    public DroneControllerBlockEntity(BlockPos pos, BlockState state) {
        super(MicraDrone.DRONE_CONTROLLER_BLOCK_ENTITY.get(), pos, state);
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
        int[] offset = LiveFarmBlockAccess.groundOffset(dirX, dirZ, gridX, gridY);
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
    public long getPoints() {
        return points;
    }

    @Override
    public void addPoints(long delta) {
        points += delta;
        setChanged();
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
    }

    /**
     * Loads this controller's script file (creating it with default content if missing) and runs it.
     * No-op (besides a log line) if a script is already running.
     */
    public void startScript(ServerPlayer requester) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        viewingPlayerUuid = requester.getUUID();
        if (scriptRunner != null && scriptRunner.getState() == DroneScriptRunner.State.RUNNING) {
            appendLog("[run] a script is already running");
            return;
        }
        logBuffer.clear();

        String source;
        try {
            BlockPos pos = getBlockPos();
            source = ScriptFileStore.loadOrCreateDefault(scriptsDir(serverLevel), pos.getX(), pos.getY(), pos.getZ());
        } catch (IOException e) {
            appendLog("[error] could not read script file: " + e.getMessage());
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
        appendLog("[run] script started");
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

    /** Sent when a {@code DroneScreen} opens, so it immediately shows log history instead of starting blank. */
    public void sendLogSnapshotTo(ServerPlayer requester) {
        viewingPlayerUuid = requester.getUUID();
        pushLogSnapshotTo(requester);
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
        if (!(level instanceof ServerLevel serverLevel) || viewingPlayerUuid == null) {
            return;
        }
        ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(viewingPlayerUuid);
        if (player != null) {
            pushLogSnapshotTo(player);
        }
    }

    private void pushLogSnapshotTo(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new DroneLogPayload(getBlockPos(), List.copyOf(logBuffer)));
    }

    /** Registered as this block's {@link net.minecraft.world.level.block.entity.BlockEntityTicker}; server-side only. */
    public static void serverTick(Level level, BlockPos pos, BlockState state, DroneControllerBlockEntity be) {
        be.pacedActionQueue.tick(level.getServer().getTickCount());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        gridX = tag.getInt("GridX");
        gridY = tag.getInt("GridY");
        points = tag.getLong("Points");
        droneEntityUuid = tag.hasUUID("DroneEntityUuid") ? tag.getUUID("DroneEntityUuid") : null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("GridX", gridX);
        tag.putInt("GridY", gridY);
        tag.putLong("Points", points);
        if (droneEntityUuid != null) {
            tag.putUUID("DroneEntityUuid", droneEntityUuid);
        }
    }
}
