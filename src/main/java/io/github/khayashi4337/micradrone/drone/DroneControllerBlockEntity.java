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

/** Holds the drone's grid position for the farm plot claimed by this controller. */
public class DroneControllerBlockEntity extends BlockEntity implements DroneGridState {
    /** MVP: fixed-size square grid (see Phase 1 design doc). */
    private static final int WORLD_SIZE = 5;

    /**
     * Temporary stand-in for Phase 1 task 5 (real script file + GUI trigger): till and plant two
     * cells so right-clicking the controller produces a visible result while task 5 isn't built yet.
     */
    private static final String DEMO_SCRIPT = """
            till()
            plant("wheat")
            move("east")
            till()
            plant("wheat")
            """;

    private final PacedActionQueue pacedActionQueue = new PacedActionQueue();

    // Written only on the main thread (paced action apply); read from the script worker thread too.
    private volatile int gridX = 0;
    private volatile int gridY = 0;

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
        return WORLD_SIZE;
    }

    /** See {@link #DEMO_SCRIPT}. No-op if a script is already running. */
    public void runDemoScript() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (scriptRunner != null && scriptRunner.getState() == DroneScriptRunner.State.RUNNING) {
            return;
        }
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
