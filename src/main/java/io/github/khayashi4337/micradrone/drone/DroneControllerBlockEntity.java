package io.github.khayashi4337.micradrone.drone;

import java.util.List;
import java.util.function.Consumer;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.github.khayashi4337.micradrone.lang.Lexer;
import io.github.khayashi4337.micradrone.lang.Parser;
import io.github.khayashi4337.micradrone.lang.ast.Stmt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

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

    /**
     * Temporary stand-in for Phase 1 task 5 (real script file + GUI trigger): till and plant two
     * cells so right-clicking the controller produces a visible result while task 5 isn't built yet.
     */
    private static final String DEMO_SCRIPT = """
            print(get_world_size())
            till()
            plant("wheat")
            move("east")
            till()
            plant("wheat")
            """;

    private final PacedActionQueue pacedActionQueue = new PacedActionQueue();

    // Written only on the main thread (paced action apply, or scanForCornerMarker); read from the
    // script worker thread too.
    private volatile int gridX = 0;
    private volatile int gridY = 0;
    private volatile int worldSize = DEFAULT_WORLD_SIZE;
    private volatile int dirX = 1;
    private volatile int dirZ = 1;

    private DroneScriptRunner scriptRunner;

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

    /** See {@link #DEMO_SCRIPT}. No-op if a script is already running. */
    public void runDemoScript() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (scriptRunner != null && scriptRunner.getState() == DroneScriptRunner.State.RUNNING) {
            return;
        }
        scanForCornerMarker(serverLevel);
        setGridPos(0, 0); // every run starts the drone back at the plot's origin cell
        MainThreadGateway gateway = new ServerMainThreadGateway(serverLevel.getServer());
        FarmBlockAccess farm = new LiveFarmBlockAccess(serverLevel, getBlockPos(), this);
        Consumer<String> log = msg -> MicraDrone.LOGGER.info("[drone {}] {}", getBlockPos(), msg);
        LiveDroneApi api = new LiveDroneApi(gateway, pacedActionQueue, this, farm, log);
        scriptRunner = new DroneScriptRunner(api, log);
        List<Stmt> program = new Parser(new Lexer(DEMO_SCRIPT).scan()).parseProgram();
        scriptRunner.start(program);
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
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("GridX", gridX);
        tag.putInt("GridY", gridY);
    }
}
