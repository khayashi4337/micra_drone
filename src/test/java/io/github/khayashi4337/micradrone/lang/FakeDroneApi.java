package io.github.khayashi4337.micradrone.lang;

import java.util.ArrayList;
import java.util.List;

/** Minimal in-memory stand-in for a real drone/farm, used to exercise the interpreter in tests. */
final class FakeDroneApi implements DroneApi {
    private final int size;
    private int x = 0;
    private int y = 0;
    private final boolean[][] tilled;
    /** -1 = no crop, otherwise the crop's "age"; matureAge or higher can be harvested. */
    private final int[][] cropAge;
    private final int matureAge = 3;
    private final boolean[][] rotten;
    private long points = 0;

    final List<String> calls = new ArrayList<>();
    final List<String> printed = new ArrayList<>();

    FakeDroneApi(int size) {
        this.size = size;
        this.tilled = new boolean[size][size];
        this.cropAge = new int[size][size];
        this.rotten = new boolean[size][size];
        for (int[] row : cropAge) java.util.Arrays.fill(row, -1);
    }

    void setCropAge(int atX, int atY, int age) {
        cropAge[atX][atY] = age;
    }

    void setRotten(int atX, int atY, boolean isRotten) {
        rotten[atX][atY] = isRotten;
    }

    int posXInt() { return x; }
    int posYInt() { return y; }

    @Override
    public boolean move(String direction) {
        calls.add("move:" + direction);
        int nx = x, ny = y;
        switch (direction) {
            case "north" -> ny -= 1;
            case "south" -> ny += 1;
            case "east" -> nx += 1;
            case "west" -> nx -= 1;
            default -> throw new IllegalArgumentException("bad direction: " + direction);
        }
        if (nx < 0 || nx >= size || ny < 0 || ny >= size) return false;
        x = nx;
        y = ny;
        return true;
    }

    @Override
    public boolean till() {
        calls.add("till");
        tilled[x][y] = true;
        return true;
    }

    @Override
    public boolean plant(String crop) {
        calls.add("plant:" + crop);
        if (tilled[x][y] && (cropAge[x][y] == -1 || rotten[x][y])) {
            cropAge[x][y] = 0;
            rotten[x][y] = false;
            return true;
        }
        return false;
    }

    @Override
    public boolean harvest() {
        calls.add("harvest");
        // Matches the original game: a rotten pumpkin can be harvested (the attempt succeeds and
        // clears the cell) but yields no points - see LiveFarmBlockAccess#attemptHarvest.
        if (rotten[x][y]) {
            cropAge[x][y] = -1;
            rotten[x][y] = false;
            return true;
        }
        if (cropAge[x][y] >= matureAge) {
            cropAge[x][y] = -1;
            points += 1;
            return true;
        }
        return false;
    }

    @Override
    public boolean canHarvest() {
        calls.add("can_harvest");
        return cropAge[x][y] >= matureAge;
    }

    @Override
    public boolean isRotten() {
        calls.add("is_rotten");
        return rotten[x][y];
    }

    @Override
    public double getPosX() {
        return x;
    }

    @Override
    public double getPosY() {
        return y;
    }

    @Override
    public double getWorldSize() {
        return size;
    }

    @Override
    public double getPoints() {
        return points;
    }

    @Override
    public double getPoints(String crop) {
        // The fake only ever deals in one implicit crop ("wheat"), matching the real game's current
        // (wheat-only) state - see LiveFarmBlockAccess.POINTS_PER_WHEAT_HARVEST.
        return "wheat".equals(crop) ? points : 0;
    }

    @Override
    public void print(String text) {
        printed.add(text);
    }
}
