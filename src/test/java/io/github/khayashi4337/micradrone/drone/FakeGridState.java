package io.github.khayashi4337.micradrone.drone;

import java.util.HashMap;
import java.util.Map;

final class FakeGridState implements DroneGridState {
    private int x;
    private int y;
    private final int size;
    private final Map<String, Long> pointsByCrop = new HashMap<>();

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

    @Override
    public long getPoints(String crop) {
        return pointsByCrop.getOrDefault(crop, 0L);
    }

    @Override
    public void addPoints(String crop, long delta) {
        pointsByCrop.merge(crop, delta, Long::sum);
    }

    @Override
    public Map<String, Long> pointsByCrop() {
        return Map.copyOf(pointsByCrop);
    }
}
