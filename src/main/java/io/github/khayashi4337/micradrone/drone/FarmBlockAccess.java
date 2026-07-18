package io.github.khayashi4337.micradrone.drone;

/**
 * Real-block operations for the farm cell under the drone. All methods must only be called from
 * the main thread (they read/write live world block state).
 */
public interface FarmBlockAccess {
    Attempt attemptTill();

    Attempt attemptPlant(String crop);

    Attempt attemptHarvest();

    boolean canHarvest();
}
