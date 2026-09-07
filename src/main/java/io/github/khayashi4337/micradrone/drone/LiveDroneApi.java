package io.github.khayashi4337.micradrone.drone;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.github.khayashi4337.micradrone.lang.DroneApi;
import io.github.khayashi4337.micradrone.lang.ScriptStoppedException;

/**
 * Real {@link DroneApi} backing a placed drone. World-mutating calls are dispatched to the main
 * thread and paced via {@link PacedActionQueue} so the drone visibly takes time to act, mirroring
 * the original game's per-command "operations" cost. till/plant/harvest delegate the actual block
 * work to {@link FarmBlockAccess}.
 */
public final class LiveDroneApi implements DroneApi {
    private static final int ACTION_DELAY_TICKS = 4;
    private static final long MAIN_THREAD_TIMEOUT_SECONDS = 5;
    /** ~1 in-game day/night cycle (20 minutes at 20 TPS) - a generous, sane cap on a single sleep_ticks() call. */
    private static final long MAX_SLEEP_TICKS = 24_000;

    private final MainThreadGateway gateway;
    private final PacedActionQueue pacedQueue;
    private final DroneGridState grid;
    private final FarmBlockAccess farm;
    private final Consumer<String> logSink;

    public LiveDroneApi(MainThreadGateway gateway, PacedActionQueue pacedQueue, DroneGridState grid,
            FarmBlockAccess farm, Consumer<String> logSink) {
        this.gateway = gateway;
        this.pacedQueue = pacedQueue;
        this.grid = grid;
        this.farm = farm;
        this.logSink = logSink;
    }

    @Override
    public boolean move(String direction) {
        int dx;
        int dy;
        switch (direction) {
            case "north" -> { dx = 0; dy = -1; }
            case "south" -> { dx = 0; dy = 1; }
            case "east" -> { dx = 1; dy = 0; }
            case "west" -> { dx = -1; dy = 0; }
            default -> throw new IllegalArgumentException("unknown direction: '" + direction + "'");
        }
        return dispatch(() -> {
            int nx = grid.gridX() + dx;
            int ny = grid.gridY() + dy;
            boolean inBounds = nx >= 0 && nx < grid.worldSize() && ny >= 0 && ny < grid.worldSize();
            return new Attempt(inBounds, () -> grid.setGridPos(nx, ny));
        });
    }

    @Override
    public boolean till() {
        return dispatch(farm::attemptTill);
    }

    @Override
    public boolean plant(String crop) {
        return dispatch(() -> farm.attemptPlant(crop));
    }

    @Override
    public boolean harvest() {
        return dispatch(farm::attemptHarvest);
    }

    @Override
    public void doAFlip() {
        dispatch(() -> new Attempt(true, grid::triggerDroneFlip));
    }

    @Override
    public boolean canHarvest() {
        return queryMainThread(farm::canHarvest);
    }

    @Override
    public boolean isRotten() {
        return queryMainThread(farm::isRotten);
    }

    @Override
    public double measure() {
        return queryMainThread(farm::giantPumpkinSide);
    }

    @Override
    public double getPosX() {
        return grid.gridX();
    }

    @Override
    public double getPosY() {
        return grid.gridY();
    }

    @Override
    public double getWorldSize() {
        return grid.worldSize();
    }

    /**
     * get_world_x()/y()/z(): the drone's current grid cell translated to real world block
     * coordinates, anchored on the controller's own position - the same {@link PlotGeometry}
     * math {@code syncDronePosition}/{@code resolveAngler} already use to place the visible drone
     * entity and the fishing angler, just exposed to scripts directly. No new movement model: the
     * drone is still confined to the same worldSize x worldSize plot, this just answers "where is
     * that, in real coordinates" for math against an arbitrary landmark (e.g. a saved waypoint).
     */
    @Override
    public double getWorldX() {
        int[] offset = PlotGeometry.groundOffset(grid.dirX(), grid.dirZ(), grid.gridX(), grid.gridY());
        return grid.originX() + offset[0];
    }

    @Override
    public double getWorldY() {
        return grid.originY() + 1.0 + grid.groundYOffset();
    }

    @Override
    public double getWorldZ() {
        int[] offset = PlotGeometry.groundOffset(grid.dirX(), grid.dirZ(), grid.gridX(), grid.gridY());
        return grid.originZ() + offset[1];
    }

    @Override
    public String getDimension() {
        return grid.dimensionId();
    }

    @Override
    public double getPoints() {
        return grid.pointsByCrop().values().stream().mapToLong(Long::longValue).sum();
    }

    @Override
    public double getPoints(String crop) {
        return grid.getPoints(crop);
    }

    @Override
    public void setOutput(boolean powered) {
        dispatch(() -> new Attempt(true, () -> grid.setRedstoneOutput(powered)));
    }

    @Override
    public boolean getOutput() {
        return queryMainThread(grid::redstoneOutput);
    }

    @Override
    public void pairWith(String id) {
        dispatch(() -> new Attempt(true, () -> grid.setPairTarget(id)));
    }

    @Override
    public boolean isPaired() {
        return queryMainThread(grid::isPaired);
    }

    // ---- perception (GitHub issue #10) ----
    // All read-only, so they take canHarvest()'s route: a plain main-thread query with no pacing
    // delay. Reading the world costs a script nothing but the main-thread round-trip, which keeps
    // "look before you act" free enough that scripts are encouraged to actually do it.

    @Override
    public String getGround() {
        return queryMainThread(farm::groundBlockName);
    }

    @Override
    public String getBlockAbove() {
        return queryMainThread(farm::blockAboveName);
    }

    @Override
    public double getTime() {
        return queryMainThread(farm::dayTime);
    }

    @Override
    public String getWeather() {
        return queryMainThread(farm::weather);
    }

    @Override
    public String getBiome() {
        return queryMainThread(farm::biomeName);
    }

    @Override
    public double getLight() {
        return queryMainThread(farm::lightLevel);
    }

    @Override
    public String getPlotId() {
        return queryMainThread(farm::plotId);
    }

    @Override
    public void print(String text) {
        logSink.accept(text);
    }

    @Override
    public boolean castLine() {
        return dispatch(this::castAttempt);
    }

    @Override
    public boolean reelIn() {
        return dispatch(this::reelAttempt);
    }

    @Override
    public boolean isFishing() {
        return queryMainThread(grid::isFishing);
    }

    @Override
    public boolean isBobberBobbing() {
        return queryMainThread(grid::isBobberBobbing);
    }

    @Override
    public boolean didFishBite() {
        return queryMainThread(grid::didFishBite);
    }

    @Override
    public boolean isOpenWaterCast() {
        return queryMainThread(grid::isOpenWaterCast);
    }

    @Override
    public double getRodDurability() {
        return queryMainThread(grid::rodDurability);
    }

    @Override
    public boolean isAnvil() {
        return queryMainThread(grid::isAnvil);
    }

    @Override
    public double getRepairCost() {
        return queryMainThread(grid::repairCost);
    }

    @Override
    public boolean repairRod() {
        return dispatch(() -> new Attempt(grid.repairRod(), () -> {
        }));
    }

    /**
     * cast_line()/reel_in() decide success/failure by ACTUALLY performing the vanilla call
     * (unlike move/till/plant, there's no way to check "would this succeed" without doing it -
     * {@code FishingRodItem.use()} has no side-effect-free query form) - so, unlike every other
     * paced action here, the mutation happens up front and {@link Attempt#apply} is a no-op.
     * Pacing is still applied afterward, matching how every other world-changing command costs the
     * same 4-tick delay.
     */
    private Attempt castAttempt() {
        return new Attempt(grid.castLine(), () -> {
        });
    }

    private Attempt reelAttempt() {
        return new Attempt(grid.reelIn(), () -> {
        });
    }

    /**
     * Waits {@code ticks} game ticks without touching the world - RTOS-style tasks (create_task)
     * use this to pace/yield themselves, sharing the exact same tick-driven pacing as move/till/
     * plant/harvest (see {@link #dispatch(long, Supplier)}). Non-finite ticks (this language has
     * essentially no way to produce one - {@code /} and {@code %} already stop on division by
     * zero) fall back to 0; the value is otherwise clamped to {@link #MAX_SLEEP_TICKS} so an
     * absurd request can't produce an absurd wait. Rounds a fractional tick count UP (0.9 becomes
     * 1, not 0) - matching create_task's budget_ticks handling, and avoiding a script's arithmetic
     * producing a barely-fractional value silently sleeping for nothing at all.
     */
    @Override
    public void sleepTicks(double ticks) {
        long n = Double.isFinite(ticks) ? (long) Math.min(MAX_SLEEP_TICKS, Math.ceil(Math.max(0, ticks))) : 0;
        dispatch(n, () -> new Attempt(true, () -> { }));
    }

    /** Existing callers (move/till/plant/harvest/doAFlip/setOutput/pairWith) - unchanged, always paced by {@link #ACTION_DELAY_TICKS}. */
    private boolean dispatch(Supplier<Attempt> attempt) {
        return dispatch(ACTION_DELAY_TICKS, attempt);
    }

    /**
     * Decides success/failure of {@code attempt} on the main thread right away, then defers the
     * actual mutation - and unblocking the caller - until {@code successDelayTicks} game ticks
     * have elapsed (0 on failure). {@code blockOn}'s wait is capped at
     * {@link #timeoutForTicks(long)}, not the bare anomaly-detection margin, so a legitimately
     * long {@code sleep_ticks} call isn't mistaken for a stuck main thread.
     */
    private boolean dispatch(long successDelayTicks, Supplier<Attempt> attempt) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        gateway.runOnMainThread(() -> {
            Attempt result = attempt.get();
            long delay = result.succeeded() ? successDelayTicks : 0;
            long readyAt = gateway.currentTick() + delay;
            pacedQueue.submit(readyAt, () -> {
                if (result.succeeded()) {
                    result.apply().run();
                }
                future.complete(result.succeeded());
            });
        });
        return blockOn(future, timeoutForTicks(successDelayTicks));
    }

    /**
     * The 5s anomaly-detection margin, plus however long {@code ticks} could plausibly take under
     * real (possibly severe) server lag - NOT the nominal 20 TPS. An earlier version divided by 20
     * (assuming the server always runs at full speed), which contradicted this very method's
     * purpose: under sustained lag - exactly the condition sleep_ticks is meant to stay correct
     * under - reaching a given tick number for real takes longer than the nominal 20-TPS math
     * predicts, so that version would spuriously time out a legitimately-waiting, merely slow
     * server (Codex review finding). {@link #MIN_ASSUMED_TPS} is a deliberately pessimistic floor
     * (a tenth of normal speed) to tolerate that, at the cost of a much later "the main thread is
     * genuinely dead" failure for a long sleep_ticks() call specifically - this is an honest
     * tradeoff, not a guarantee: a server wedged for longer than this still eventually reports a
     * timeout instead of hanging forever, but there is no timeout formula that is both tight enough
     * to catch a truly hung server quickly and loose enough to never misfire under some amount of
     * sustained real lag, since the two look identical from here for long enough.
     */
    private static final long MIN_ASSUMED_TPS = 2;

    private long timeoutForTicks(long ticks) {
        long pessimisticSeconds = (ticks + MIN_ASSUMED_TPS - 1) / MIN_ASSUMED_TPS; // ceiling division
        return MAIN_THREAD_TIMEOUT_SECONDS + pessimisticSeconds;
    }

    /** Runs a read-only query on the main thread and returns its result immediately (no pacing delay). */
    private <T> T queryMainThread(Supplier<T> query) {
        CompletableFuture<T> future = new CompletableFuture<>();
        gateway.runOnMainThread(() -> future.complete(query.get()));
        return blockOn(future, MAIN_THREAD_TIMEOUT_SECONDS);
    }

    private <T> T blockOn(CompletableFuture<T> future, long timeoutSeconds) {
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScriptStoppedException();
        } catch (TimeoutException e) {
            throw new RuntimeException("drone action timed out waiting for the main thread", e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }
}
