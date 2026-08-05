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
     * cast_line(): throws a real {@code FishingHook} the same way a player's right-click does (via
     * the resolved angler - see {@code DroneControllerBlockEntity#resolveAngler}), so vanilla's own
     * enchantment/physics/timing logic runs unmodified. False (no-op) if already fishing, or if this
     * controller has no rod ({@link #currentRod}/task #66's swap logic decides that).
     */
    boolean castLine();

    /**
     * reel_in(): retrieves the currently-out hook the same way a player's second right-click does -
     * real loot table roll, real durability damage, all vanilla. False (no-op) if not currently
     * fishing (nothing was cast, or it was already reeled in).
     */
    boolean reelIn();

    /** Read-only: true if a hook is currently out (cast_line() succeeded and reel_in() hasn't run yet). */
    boolean isFishing();

    /** Read-only: true if a hook is out AND has landed in water (still flying through the air otherwise). */
    boolean isBobberBobbing();

    /** Read-only: true if a hook is out and a fish is currently biting (the active bite window). */
    boolean didFishBite();

    /** Read-only: true if the current cast landed in a valid open-water fishing spot. */
    boolean isOpenWaterCast();

    /**
     * Read-only: the current rod's remaining uses (max durability minus damage taken so far), or -1
     * if no rod is currently held (a broken rod's stack becomes empty rather than reaching a real
     * zero-durability state, so -1 is the unambiguous "no rod" sentinel).
     */
    double rodDurability();
}
