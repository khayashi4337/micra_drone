package io.github.khayashi4337.micradrone.lang;

/**
 * Bridge between the interpreter and the actual drone/world. Implementations
 * are responsible for any thread hand-off to Minecraft's main thread; from
 * the interpreter's point of view every method here is a plain blocking call
 * made from the script's worker thread.
 */
public interface DroneApi {
    /** direction is one of "north"/"south"/"east"/"west". Returns true if the drone actually moved. */
    boolean move(String direction);

    /** Returns true if the ground was tilled into farmland. */
    boolean till();

    /** Returns true if the crop was planted. crop is currently only "wheat". */
    boolean plant(String crop);

    /** Returns true if a mature crop was harvested. */
    boolean harvest();

    /**
     * A fun, no-effect action - matches the reference game (The Farmer Was Replaced), where it's
     * paired with harvest() as the two commands a fresh plot starts with. Costs the same action
     * pacing as move/till/plant/harvest; has no effect on the farm or points.
     */
    void doAFlip();

    /** Read-only: true if the crop under the drone is ready to harvest. */
    boolean canHarvest();

    /** Read-only: true if the cell under the drone holds a defective ("rotten") pumpkin. */
    boolean isRotten();

    double getPosX();

    double getPosY();

    double getWorldSize();

    /** Read-only: this plot's current resource point balance, summed across every crop type. */
    double getPoints();

    /** Read-only: this plot's current point balance for one crop type only (0 if it has none). */
    double getPoints(String crop);

    /**
     * Sets the paired Corner Marker's redstone output on (full power) or off - lets a script signal
     * the world outside the plot (a lamp, a door, another contraption). Does nothing if no marker is
     * currently paired with this plot. Persists until changed again, independent of whether a script
     * is running.
     */
    void setOutput(boolean powered);

    /** Read-only: the paired Corner Marker's current redstone output state (false if none is paired). */
    boolean getOutput();

    /**
     * Declares (or, with "", clears) the id (see get_plot_id()) this plot's marker wants to pair
     * with - one-sided by itself, see {@link #isPaired()}. Does nothing if no marker is currently
     * paired with this plot.
     */
    void pairWith(String id);

    /**
     * Read-only: true only if this plot's marker AND the marker it named both name each other back
     * (mutual pairing).
     */
    boolean isPaired();

    // ---- perception: the world around the drone, not just its own grid (GitHub issue #10) ----
    // Everything below is read-only. Block/biome names come back without the "minecraft:" prefix
    // (so a script compares against plain "dirt", "plains"); anything from a mod keeps its namespace.

    /** Read-only: the block the drone is standing over, e.g. "farmland", "dirt", "sand", "water". */
    String getGround();

    /** Read-only: the block in the drone's own cell - the crop it tends, or "air" when empty. */
    String getBlockAbove();

    /** Read-only: time of day in ticks, 0..23999 (0 sunrise, 6000 noon, 12000 sunset, 18000 midnight). */
    double getTime();

    /** Read-only: "clear", "rain", or "thunder" (thunder wins, since a thunderstorm also rains). */
    String getWeather();

    /** Read-only: the biome at the drone's cell, e.g. "plains", "desert", "jungle". */
    String getBiome();

    /** Read-only: light level at the drone's cell, 0..15 (vanilla crops need 9 or more to grow). */
    double getLight();

    /**
     * Read-only: this plot's Corner Marker id - the friendly name if one was set via anvil, else a
     * short form of its auto-assigned id (see {@code CornerMarkerBlockEntity#displayId}). Empty
     * string if no marker is currently paired with this plot.
     */
    String getPlotId();

    /** Appends text to the script's log panel. */
    void print(String text);
}
