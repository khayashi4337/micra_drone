package io.github.khayashi4337.micradrone.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LiveDroneApiTest {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutdown() {
        worker.shutdownNow();
    }

    private static LiveDroneApi newApi(FakeMainThreadGateway gateway, PacedActionQueue queue, FakeGridState grid,
            FakeFarmBlockAccess farm, java.util.function.Consumer<String> logSink) {
        return new LiveDroneApi(gateway, queue, grid, farm, logSink);
    }

    @Test
    void successfulMoveTakesFourTicksAndUpdatesPosition() throws Exception {
        FakeMainThreadGateway gateway = new FakeMainThreadGateway();
        PacedActionQueue queue = new PacedActionQueue();
        FakeGridState grid = new FakeGridState(5);
        LiveDroneApi api = newApi(gateway, queue, grid, new FakeFarmBlockAccess(), msg -> {});

        Future<Boolean> result = worker.submit(() -> api.move("east"));

        gateway.awaitQueuedWork(2000);
        gateway.pump(); // Phase A on the "main thread": decides success, schedules Phase B at tick+4

        assertFalse(result.isDone());
        gateway.advanceTo(3, queue);
        assertFalse(result.isDone());
        assertEquals(0, grid.gridX());

        gateway.advanceTo(4, queue);
        assertTrue(result.get(2, TimeUnit.SECONDS));
        assertEquals(1, grid.gridX());
        assertEquals(0, grid.gridY());
    }

    @Test
    void moveOutOfBoundsFailsImmediatelyAndDoesNotMove() throws Exception {
        FakeMainThreadGateway gateway = new FakeMainThreadGateway();
        PacedActionQueue queue = new PacedActionQueue();
        FakeGridState grid = new FakeGridState(5); // starts at (0,0)
        LiveDroneApi api = newApi(gateway, queue, grid, new FakeFarmBlockAccess(), msg -> {});

        Future<Boolean> result = worker.submit(() -> api.move("north"));

        gateway.awaitQueuedWork(2000);
        gateway.pump();
        gateway.advanceTo(0, queue); // 0-tick delay on failure: ready at the same tick

        assertFalse(result.get(2, TimeUnit.SECONDS));
        assertEquals(0, grid.gridX());
        assertEquals(0, grid.gridY());
    }

    @Test
    void printGoesStraightToLogSinkWithoutTouchingTheGateway() {
        FakeMainThreadGateway gateway = new FakeMainThreadGateway();
        PacedActionQueue queue = new PacedActionQueue();
        FakeGridState grid = new FakeGridState(5);
        List<String> logs = new ArrayList<>();
        LiveDroneApi api = newApi(gateway, queue, grid, new FakeFarmBlockAccess(), logs::add);

        api.print("hello");

        assertEquals(List.of("hello"), logs);
        assertFalse(gateway.hasQueuedWork());
    }

    @Test
    void unknownDirectionThrows() {
        FakeMainThreadGateway gateway = new FakeMainThreadGateway();
        PacedActionQueue queue = new PacedActionQueue();
        FakeGridState grid = new FakeGridState(5);
        LiveDroneApi api = newApi(gateway, queue, grid, new FakeFarmBlockAccess(), msg -> {});

        assertThrows(IllegalArgumentException.class, () -> api.move("up"));
    }

    @Test
    void tillPlantAndHarvestGoThroughToFarmBlockAccess() throws Exception {
        FakeMainThreadGateway gateway = new FakeMainThreadGateway();
        PacedActionQueue queue = new PacedActionQueue();
        FakeGridState grid = new FakeGridState(5);
        FakeFarmBlockAccess farm = new FakeFarmBlockAccess();
        LiveDroneApi api = newApi(gateway, queue, grid, farm, msg -> {});

        Future<Boolean> tillResult = worker.submit(api::till);
        gateway.awaitQueuedWork(2000);
        gateway.pump();
        gateway.advanceTo(4, queue);
        assertTrue(tillResult.get(2, TimeUnit.SECONDS));
        assertTrue(farm.isTilled());

        Future<Boolean> plantResult = worker.submit(() -> api.plant("wheat"));
        gateway.awaitQueuedWork(2000);
        gateway.pump();
        gateway.advanceTo(8, queue);
        assertTrue(plantResult.get(2, TimeUnit.SECONDS));
        assertEquals("wheat", farm.plantedCrop());

        // not mature yet
        Future<Boolean> earlyHarvest = worker.submit(api::harvest);
        gateway.awaitQueuedWork(2000);
        gateway.pump();
        gateway.advanceTo(8, queue); // failure: 0-tick delay
        assertFalse(earlyHarvest.get(2, TimeUnit.SECONDS));
        assertEquals("wheat", farm.plantedCrop());

        farm.setMature(true);
        Future<Boolean> harvestResult = worker.submit(api::harvest);
        gateway.awaitQueuedWork(2000);
        gateway.pump();
        gateway.advanceTo(12, queue);
        assertTrue(harvestResult.get(2, TimeUnit.SECONDS));
        assertEquals(null, farm.plantedCrop());
    }

    /**
     * Perception (issue #10) has to take canHarvest()'s route, not till()'s: the read must happen on
     * the main thread (it touches live world state) but must not cost the script a pacing delay, or
     * "look before you act" would be strictly slower than acting blindly.
     */
    @Test
    void perceptionReadsGoThroughTheMainThreadWithNoPacingDelay() throws Exception {
        FakeMainThreadGateway gateway = new FakeMainThreadGateway();
        PacedActionQueue queue = new PacedActionQueue();
        FakeGridState grid = new FakeGridState(5);
        FakeFarmBlockAccess farm = new FakeFarmBlockAccess();
        LiveDroneApi api = newApi(gateway, queue, grid, farm, msg -> {});

        Future<String> ground = worker.submit(api::getGround);
        gateway.awaitQueuedWork(2000);
        gateway.pump(); // no pacedQueue tick needed: the query completes as soon as it's run
        assertEquals("dirt", ground.get(2, TimeUnit.SECONDS));

        Future<String> weather = worker.submit(api::getWeather);
        gateway.awaitQueuedWork(2000);
        gateway.pump();
        assertEquals("clear", weather.get(2, TimeUnit.SECONDS));

        Future<Double> light = worker.submit(api::getLight);
        gateway.awaitQueuedWork(2000);
        gateway.pump();
        assertEquals(15.0, light.get(2, TimeUnit.SECONDS));
    }

    /** A perception read must never be answered off the main thread - the world isn't safe to touch there. */
    @Test
    void aPerceptionReadBlocksUntilTheMainThreadRunsIt() throws Exception {
        FakeMainThreadGateway gateway = new FakeMainThreadGateway();
        PacedActionQueue queue = new PacedActionQueue();
        FakeGridState grid = new FakeGridState(5);
        LiveDroneApi api = newApi(gateway, queue, grid, new FakeFarmBlockAccess(), msg -> {});

        Future<String> biome = worker.submit(api::getBiome);
        gateway.awaitQueuedWork(2000);

        assertFalse(biome.isDone(), "the read must wait for the main thread rather than answering itself");
        gateway.pump();
        assertEquals("plains", biome.get(2, TimeUnit.SECONDS));
    }

    @Test
    void canHarvestIsAnImmediateMainThreadReadWithNoPacingDelay() throws Exception {
        FakeMainThreadGateway gateway = new FakeMainThreadGateway();
        PacedActionQueue queue = new PacedActionQueue();
        FakeGridState grid = new FakeGridState(5);
        FakeFarmBlockAccess farm = new FakeFarmBlockAccess();
        farm.setMature(false);
        LiveDroneApi api = newApi(gateway, queue, grid, farm, msg -> {});

        Future<Boolean> result = worker.submit(api::canHarvest);
        gateway.awaitQueuedWork(2000);
        gateway.pump(); // no pacedQueue involvement needed: query completes as soon as it's run

        assertFalse(result.get(2, TimeUnit.SECONDS));
    }

    @Test
    void setOutputWritesThroughToTheGridAndGetOutputReadsItBack() throws Exception {
        FakeMainThreadGateway gateway = new FakeMainThreadGateway();
        PacedActionQueue queue = new PacedActionQueue();
        FakeGridState grid = new FakeGridState(5);
        LiveDroneApi api = newApi(gateway, queue, grid, new FakeFarmBlockAccess(), msg -> {});

        assertFalse(grid.redstoneOutput());

        worker.submit(() -> api.setOutput(true));
        gateway.awaitQueuedWork(2000);
        gateway.pump(); // decides success (always true) and schedules the actual write
        gateway.advanceTo(4, queue); // ACTION_DELAY_TICKS - the write only lands once this fires

        assertTrue(grid.redstoneOutput());

        Future<Boolean> read = worker.submit(api::getOutput);
        gateway.awaitQueuedWork(2000);
        gateway.pump(); // an immediate read, like canHarvest - no pacedQueue involvement
        assertTrue(read.get(2, TimeUnit.SECONDS));
    }
}
