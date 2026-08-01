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

    /** Starts (or restarts) a one-shot cosmetic spin on the visible drone entity - see do_a_flip(). */
    void triggerDroneFlip();

    /**
     * Sets this plot's own Corner Marker's redstone output on (full power) or off - see set_output().
     * Silently does nothing if this plot has no marker (none placed, or none found on a diagonal).
     */
    void setRedstoneOutput(boolean powered);

    /** This plot's own marker's current redstone output state - false if it has no marker, or none has been set yet. */
    boolean redstoneOutput();

    /**
     * pair_with(): declares (or, with "", clears) the id this plot's own marker wants to mutually
     * pair with - a DIFFERENT relationship than "the marker found for this plot" above (that one is
     * always this plot's own, by diagonal scan; this one can name any marker anywhere in the world).
     * One-sided by itself - see {@link #isPaired()}. Does nothing if this plot has no marker of its own.
     */
    void setPairTarget(String id);

    /**
     * is_paired(): true only if this plot's own marker names some other marker AND that other marker
     * names this one back (mutual). False if this plot has no marker of its own, that marker has no
     * pair target set, or the target hasn't (yet) named this marker back.
     */
    boolean isPaired();
}
