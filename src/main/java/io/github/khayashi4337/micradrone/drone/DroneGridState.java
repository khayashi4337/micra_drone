package io.github.khayashi4337.micradrone.drone;

import java.util.Map;

/** Narrow read/write view of a drone's position on its farm grid, kept separate from BlockEntity for testability. */
public interface DroneGridState {
    int gridX();

    int gridY();

    void setGridPos(int x, int y);

    /** Side length of the (square) farm grid. */
    int worldSize();

    /** +1 or -1: which world X direction grid column 0 starts in, relative to the controller. */
    int dirX();

    /** +1 or -1: which world Z direction grid row 0 starts in, relative to the controller. */
    int dirZ();

    /**
     * 0 (embedded controller, farmland at the controller's own Y) or -1 (surface-mounted controller,
     * farmland one block down) - see {@link CornerMarkerScan#groundYOffset}.
     */
    int groundYOffset();

    /** This plot's point balance for one crop type (0 if it has never earned any). Never resets on its own. */
    long getPoints(String crop);

    /** Adds (or, with a negative delta, removes) points earned from {@code crop}. */
    void addPoints(String crop, long delta);

    /** Crop type -> point balance, for every crop type this plot has ever earned points from. */
    Map<String, Long> pointsByCrop();

    /** True if this plot may plant {@code crop} - "wheat" always is; others need buying in the shop. */
    boolean isUnlocked(String crop);

    /**
     * The fallback for a crop that is NOT unlocked: takes one real seed item for {@code crop}
     * (a carrot, pumpkin seeds) out of the controller owner's inventory and returns true, or
     * returns false if the owner is offline or has none. The shop unlock stays the "game" way -
     * free planting forever - but a player holding actual carrots may plant them too (林さん's
     * call: prefer the game's mechanism, accept real items when that isn't there).
     */
    boolean takeSeedFromOwner(String crop);

    /** Starts (or restarts) a one-shot cosmetic spin on the visible drone entity - see do_a_flip(). */
    void triggerDroneFlip();

    /**
     * Sets the paired Corner Marker's redstone output on (full power) or off - see set_output().
     * Silently does nothing if no marker is currently paired with this plot.
     */
    void setRedstoneOutput(boolean powered);

    /** Current redstone output state - false if no marker is paired, or none has been set yet. */
    boolean redstoneOutput();

    /**
     * pair_with(): declares (or, with "", clears) the id this plot's marker wants to pair with.
     * One-sided by itself - see {@link #isPaired()}. Does nothing if no marker is paired with this
     * plot.
     */
    void setPairTarget(String id);

    /**
     * is_paired(): true only if this plot's marker names some other marker AND that other marker
     * names this one back (mutual). False if no marker is paired with this plot, no pair target has
     * been set, or the target hasn't (yet) named this marker back.
     */
    boolean isPaired();
}
