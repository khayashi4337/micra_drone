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
    private long dayTime = 6000; // noon
    private String weather = "clear";
    private String biome = "plains";
    private double light = 15;
    private String plotId = "";
    private boolean hasRod;
    private boolean fishing;
    private boolean bobbing;
    private boolean biting;
    private boolean openWaterCast;
    private double rodDurability = -1;
    private boolean anvil;
    private double repairCost = -1;
    private boolean repairPossible;

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

    void setHasRod(boolean hasRod) {
        this.hasRod = hasRod;
    }

    void setBobbing(boolean bobbing) {
        this.bobbing = bobbing;
    }

    void setBiting(boolean biting) {
        this.biting = biting;
    }

    void setOpenWaterCast(boolean openWaterCast) {
        this.openWaterCast = openWaterCast;
    }

    void setRodDurability(double rodDurability) {
        this.rodDurability = rodDurability;
    }

    void setAnvil(boolean anvil) {
        this.anvil = anvil;
    }

    void setRepairCost(double repairCost) {
        this.repairCost = repairCost;
    }

    void setRepairPossible(boolean repairPossible) {
        this.repairPossible = repairPossible;
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

    @Override
    public boolean castLine() {
        calls.add("cast_line");
        if (!hasRod || fishing) {
            return false;
        }
        fishing = true;
        return true;
    }

    @Override
    public boolean reelIn() {
        calls.add("reel_in");
        if (!fishing) {
            return false;
        }
        fishing = false;
        return true;
    }

    @Override
    public boolean isFishing() {
        calls.add("is_fishing");
        return fishing;
    }

    @Override
    public boolean isBobberBobbing() {
        calls.add("is_bobber_bobbing");
        return bobbing;
    }

    @Override
    public boolean didFishBite() {
        calls.add("did_fish_bite");
        return biting;
    }

    @Override
    public boolean isOpenWaterCast() {
        calls.add("is_open_water_cast");
        return openWaterCast;
    }

    @Override
    public double getRodDurability() {
        calls.add("get_rod_durability");
        return rodDurability;
    }

    @Override
    public boolean isAnvil() {
        calls.add("is_anvil");
        return anvil;
    }

    @Override
    public double getRepairCost() {
        calls.add("get_repair_cost");
        return repairCost;
    }

    @Override
    public boolean repairRod() {
        calls.add("repair_rod");
        return repairPossible;
    }
}
