package io.github.khayashi4337.micradrone.drone;

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
}
