package io.github.khayashi4337.micradrone.drone;

/**
 * The drone's window onto the live world at its own cell: the farm operations it performs there,
 * plus the perception reads it can make about its surroundings (GitHub issue #10). All methods must
 * only be called from the main thread (they read/write live world state).
 */
public interface FarmBlockAccess {
    Attempt attemptTill();

    Attempt attemptPlant(String crop);

    Attempt attemptHarvest();

    boolean canHarvest();

    /** True if the cell under the drone holds a defective ("rotten") pumpkin - see LiveFarmBlockAccess. */
    boolean isRotten();

    /** Side length of the giant pumpkin under the drone, 0 if it isn't standing on one - see LiveFarmBlockAccess. */
    int giantPumpkinSide();

    // ---- perception (issue #10) ----
    // Names are normalized through SenseNames.simplify, so a script sees "dirt" rather than
    // "minecraft:dirt". These are what let a script branch on the actual world instead of only on
    // its own bookkeeping; get_ground() in particular is the read terraforming will build on.

    /** Registry name of the block the drone stands over, e.g. "farmland", "dirt", "sand". */
    String groundBlockName();

    /** Registry name of the block in the drone's own cell - the crop it tends, or "air". */
    String blockAboveName();

    /** Time of day in ticks, 0..23999 - see {@link SenseNames#timeOfDay}. */
    long dayTime();

    /** "clear", "rain", or "thunder" - see {@link SenseNames#weather}. */
    String weather();

    /** Registry name of the biome at the drone's cell, e.g. "plains", "desert". */
    String biomeName();

    /** Light level at the drone's cell, 0..15. */
    int lightLevel();

    /** This plot's Corner Marker id (friendly name, or a short form of its auto-assigned id); empty if unpaired. */
    String plotId();
}
