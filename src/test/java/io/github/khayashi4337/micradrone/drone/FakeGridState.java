package io.github.khayashi4337.micradrone.drone;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class FakeGridState implements DroneGridState {
    private int x;
    private int y;
    private final int size;
    private final Map<String, Long> pointsByCrop = new HashMap<>();
    private final Set<String> unlockedCrops = new HashSet<>(Set.of("wheat"));
    private int flipCount;
    private boolean redstoneOutput;
    private String pairTarget = "";
    private boolean paired;

    FakeGridState(int size) {
        this.size = size;
    }

    int flipCount() {
        return flipCount;
    }

    void unlock(String crop) {
        unlockedCrops.add(crop);
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
    public int groundYOffset() {
        return 0;
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

    @Override
    public boolean isUnlocked(String crop) {
        return unlockedCrops.contains(crop);
    }

    @Override
    public void triggerDroneFlip() {
        flipCount++;
    }

    /** Real seed items the fake "owner" is holding, per crop - what takeSeedFromOwner draws from. */
    final Map<String, Integer> ownerSeeds = new java.util.HashMap<>();

    @Override
    public boolean takeSeedFromOwner(String crop) {
        int have = ownerSeeds.getOrDefault(crop, 0);
        if (have <= 0) {
            return false;
        }
        ownerSeeds.put(crop, have - 1);
        return true;
    }

    @Override
    public void setRedstoneOutput(boolean powered) {
        redstoneOutput = powered;
    }

    @Override
    public boolean redstoneOutput() {
        return redstoneOutput;
    }

    String pairTarget() {
        return pairTarget;
    }

    /** Test-only: the real mutual-pairing check lives in DroneControllerBlockEntity (Minecraft-dependent). */
    void setPairedForTest(boolean paired) {
        this.paired = paired;
    }

    @Override
    public void setPairTarget(String id) {
        pairTarget = id;
    }

    @Override
    public boolean isPaired() {
        return paired;
    }
}
