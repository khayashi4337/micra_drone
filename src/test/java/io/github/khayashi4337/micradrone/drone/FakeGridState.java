package io.github.khayashi4337.micradrone.drone;

final class FakeGridState implements DroneGridState {
    private int x;
    private int y;
    private final int size;

    FakeGridState(int size) {
        this.size = size;
    }

    @Override
    public int gridX() {
        return x;
    }

    @Override
    public int gridY() {
        return y;
    }

    @Override
    public void setGridPos(int x, int y) {
        this.x = x;
        this.y = y;
    }

    @Override
    public int worldSize() {
        return size;
    }

    @Override
    public int dirX() {
        return 1;
    }

    @Override
    public int dirZ() {
        return 1;
    }
}
