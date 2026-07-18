package io.github.khayashi4337.micradrone.drone;

/** Narrow read/write view of a drone's position on its farm grid, kept separate from BlockEntity for testability. */
public interface DroneGridState {
    int gridX();

    int gridY();

    void setGridPos(int x, int y);

    /** Side length of the (currently fixed-size) square farm grid. */
    int worldSize();
}
