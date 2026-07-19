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

    /** Read-only: true if the crop under the drone is ready to harvest. */
    boolean canHarvest();

    double getPosX();

    double getPosY();

    double getWorldSize();

    /** Read-only: this plot's current resource point balance, summed across every crop type. */
    double getPoints();

    /** Read-only: this plot's current point balance for one crop type only (0 if it has none). */
    double getPoints(String crop);

    /** Appends text to the script's log panel. */
    void print(String text);
}
