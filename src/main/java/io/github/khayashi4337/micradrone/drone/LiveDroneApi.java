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
    public boolean canHarvest() {
        return queryMainThread(farm::canHarvest);
    }

    @Override
    public boolean isRotten() {
        return queryMainThread(farm::isRotten);
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

    @Override
    public double getPoints() {
        return grid.pointsByCrop().values().stream().mapToLong(Long::longValue).sum();
    }

    @Override
    public double getPoints(String crop) {
        return grid.getPoints(crop);
    }

    @Override
    public void print(String text) {
        logSink.accept(text);
    }

    /**
     * Decides success/failure of {@code attempt} on the main thread right away, then defers the
     * actual mutation - and unblocking the caller - until the resulting pacing delay elapses.
     */
    private boolean dispatch(Supplier<Attempt> attempt) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        gateway.runOnMainThread(() -> {
            Attempt result = attempt.get();
            long delay = result.succeeded() ? ACTION_DELAY_TICKS : 0;
            long readyAt = gateway.currentTick() + delay;
            pacedQueue.submit(readyAt, () -> {
                if (result.succeeded()) {
                    result.apply().run();
                }
                future.complete(result.succeeded());
            });
        });
        return blockOn(future);
    }

    /** Runs a read-only query on the main thread and returns its result immediately (no pacing delay). */
    private <T> T queryMainThread(Supplier<T> query) {
        CompletableFuture<T> future = new CompletableFuture<>();
        gateway.runOnMainThread(() -> future.complete(query.get()));
        return blockOn(future);
    }

    private <T> T blockOn(CompletableFuture<T> future) {
        try {
            return future.get(MAIN_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
