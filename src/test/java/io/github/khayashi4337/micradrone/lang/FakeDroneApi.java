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
    /** 0 = not part of a giant pumpkin, otherwise the side length measure() reports there. */
    private final int[][] giantSide;
    private long points = 0;
    private long dayTime = 6000; // noon
    private String weather = "clear";
    private String biome = "plains";
    private double light = 15;
    private String plotId = "";
    private boolean output = false;
    private String pairTarget = "";
    private boolean pairedResult = false;

    final List<String> calls = new ArrayList<>();
    final List<String> printed = new ArrayList<>();

    FakeDroneApi(int size) {
        this.size = size;
        this.tilled = new boolean[size][size];
        this.cropAge = new int[size][size];
        this.rotten = new boolean[size][size];
        this.giantSide = new int[size][size];
        for (int[] row : cropAge) java.util.Arrays.fill(row, -1);
    }

    void setCropAge(int atX, int atY, int age) {
        cropAge[atX][atY] = age;
    }

    void setRotten(int atX, int atY, boolean isRotten) {
        rotten[atX][atY] = isRotten;
    }

    /** Pretends the cell is part of a fused giant pumpkin of the given side (0 = not giant). */
    void setGiantSide(int atX, int atY, int side) {
        giantSide[atX][atY] = side;
    }

    void setWeather(String weather) {
        this.weather = weather;
    }

    void setDayTime(long dayTime) {
        this.dayTime = dayTime;
    }

    void setLight(double light) {
        this.light = light;
    }

    void setPlotId(String plotId) {
        this.plotId = plotId;
    }

    void setPairedResult(boolean paired) {
        this.pairedResult = paired;
    }

    String pairTarget() {
        return pairTarget;
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
    public void doAFlip() {
        calls.add("do_a_flip");
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
    public double measure() {
        calls.add("measure");
        return giantSide[x][y];
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
    public void setOutput(boolean powered) {
        calls.add("set_output:" + powered);
        output = powered;
    }

    @Override
    public boolean getOutput() {
        calls.add("get_output");
        return output;
    }

    @Override
    public void pairWith(String id) {
        calls.add("pair_with:" + id);
        pairTarget = id;
    }

    @Override
    public boolean isPaired() {
        calls.add("is_paired");
        return pairedResult;
    }

    // ---- perception (issue #10) ----
    // The ground tracks this cell's real till state, so a script that branches on get_ground()
    // exercises both branches here rather than always taking the same one. The rest are fixed
    // "nice day on a plain" readings, overridable where a test needs a different world.

    @Override
    public String getGround() {
        calls.add("get_ground");
        return tilled[x][y] ? "farmland" : "dirt";
    }

    @Override
    public String getBlockAbove() {
        calls.add("get_block_above");
        return cropAge[x][y] == -1 ? "air" : "wheat";
    }

    @Override
    public double getTime() {
        calls.add("get_time");
        return dayTime;
    }

    @Override
    public String getWeather() {
        calls.add("get_weather");
        return weather;
    }

    @Override
    public String getBiome() {
        calls.add("get_biome");
        return biome;
    }

    @Override
    public double getLight() {
        calls.add("get_light");
        return light;
    }

    @Override
    public String getPlotId() {
        calls.add("get_plot_id");
        return plotId;
    }

    @Override
    public void print(String text) {
        printed.add(text);
    }
}
