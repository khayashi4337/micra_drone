package io.github.khayashi4337.micradrone.lang;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal in-memory stand-in for a real drone/farm, used to exercise the interpreter in tests.
 *
 * <p>Every method is {@code synchronized}: since create_task lets a test drive several concurrent
 * task threads against one shared FakeDroneApi instance, its internal state (this whole class
 * predates that and was never designed for concurrent access) needs the same protection a script
 * author is taught to give their own shared list/dict/set with a semaphore - see docs/design/
 * lang_rtos_task_foundation.md's "共有可変状態・並行アクセスについての注記". Doing it here with
 * {@code synchronized} is simpler than asking every task-related test to serialize its own calls,
 * and correctness (not throughput) is all a test double needs.
 */
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
    private boolean hasRod;
    private boolean fishing;
    private boolean bobbing;
    private boolean biting;
    private boolean openWaterCast;
    private double rodDurability = -1;
    private boolean anvil;
    private double repairCost = -1;
    private boolean repairPossible;
    private boolean output = false;
    private String pairTarget = "";
    private boolean pairedResult = false;
    private double worldX = 0;
    private double worldY = 64;
    private double worldZ = 0;
    private String dimension = "minecraft:overworld";

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

    synchronized void setCropAge(int atX, int atY, int age) {
        cropAge[atX][atY] = age;
    }

    synchronized void setRotten(int atX, int atY, boolean isRotten) {
        rotten[atX][atY] = isRotten;
    }

    /** Pretends the cell is part of a fused giant pumpkin of the given side (0 = not giant). */
    synchronized void setGiantSide(int atX, int atY, int side) {
        giantSide[atX][atY] = side;
    }

    synchronized void setWeather(String weather) {
        this.weather = weather;
    }

    synchronized void setWorldPos(double worldX, double worldY, double worldZ) {
        this.worldX = worldX;
        this.worldY = worldY;
        this.worldZ = worldZ;
    }

    synchronized void setDimension(String dimension) {
        this.dimension = dimension;
    }

    synchronized void setDayTime(long dayTime) {
        this.dayTime = dayTime;
    }

    synchronized void setLight(double light) {
        this.light = light;
    }

    synchronized void setPlotId(String plotId) {
        this.plotId = plotId;
    }

    synchronized void setHasRod(boolean hasRod) {
        this.hasRod = hasRod;
    }

    synchronized void setBobbing(boolean bobbing) {
        this.bobbing = bobbing;
    }

    synchronized void setBiting(boolean biting) {
        this.biting = biting;
    }

    synchronized void setOpenWaterCast(boolean openWaterCast) {
        this.openWaterCast = openWaterCast;
    }

    synchronized void setRodDurability(double rodDurability) {
        this.rodDurability = rodDurability;
    }

    synchronized void setAnvil(boolean anvil) {
        this.anvil = anvil;
    }

    synchronized void setRepairCost(double repairCost) {
        this.repairCost = repairCost;
    }

    synchronized void setRepairPossible(boolean repairPossible) {
        this.repairPossible = repairPossible;
    }

    synchronized void setPairedResult(boolean paired) {
        this.pairedResult = paired;
    }

    synchronized String pairTarget() {
        return pairTarget;
    }

    synchronized int posXInt() { return x; }
    synchronized int posYInt() { return y; }

    @Override
    public synchronized boolean move(String direction) {
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
    public synchronized boolean till() {
        calls.add("till");
        tilled[x][y] = true;
        return true;
    }

    @Override
    public synchronized boolean plant(String crop) {
        calls.add("plant:" + crop);
        if (tilled[x][y] && (cropAge[x][y] == -1 || rotten[x][y])) {
            cropAge[x][y] = 0;
            rotten[x][y] = false;
            return true;
        }
        return false;
    }

    @Override
    public synchronized boolean harvest() {
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
    public synchronized void doAFlip() {
        calls.add("do_a_flip");
    }

    @Override
    public synchronized boolean canHarvest() {
        calls.add("can_harvest");
        return cropAge[x][y] >= matureAge;
    }

    @Override
    public synchronized boolean isRotten() {
        calls.add("is_rotten");
        return rotten[x][y];
    }

    @Override
    public synchronized double measure() {
        calls.add("measure");
        return giantSide[x][y];
    }

    @Override
    public synchronized double getPosX() {
        return x;
    }

    @Override
    public synchronized double getPosY() {
        return y;
    }

    @Override
    public double getWorldSize() {
        return size; // final, never mutated - no synchronization needed
    }

    @Override
    public synchronized double getWorldX() {
        return worldX;
    }

    @Override
    public synchronized double getWorldY() {
        return worldY;
    }

    @Override
    public synchronized double getWorldZ() {
        return worldZ;
    }

    @Override
    public synchronized String getDimension() {
        return dimension;
    }

    @Override
    public synchronized double getPoints() {
        return points;
    }

    @Override
    public synchronized double getPoints(String crop) {
        // The fake only ever deals in one implicit crop ("wheat"), matching the real game's current
        // (wheat-only) state - see LiveFarmBlockAccess.POINTS_PER_WHEAT_HARVEST.
        return "wheat".equals(crop) ? points : 0;
    }

    @Override
    public synchronized void setOutput(boolean powered) {
        calls.add("set_output:" + powered);
        output = powered;
    }

    @Override
    public synchronized boolean getOutput() {
        calls.add("get_output");
        return output;
    }

    @Override
    public synchronized void pairWith(String id) {
        calls.add("pair_with:" + id);
        pairTarget = id;
    }

    @Override
    public synchronized boolean isPaired() {
        calls.add("is_paired");
        return pairedResult;
    }

    // ---- perception (issue #10) ----
    // The ground tracks this cell's real till state, so a script that branches on get_ground()
    // exercises both branches here rather than always taking the same one. The rest are fixed
    // "nice day on a plain" readings, overridable where a test needs a different world.

    @Override
    public synchronized String getGround() {
        calls.add("get_ground");
        return tilled[x][y] ? "farmland" : "dirt";
    }

    @Override
    public synchronized String getBlockAbove() {
        calls.add("get_block_above");
        return cropAge[x][y] == -1 ? "air" : "wheat";
    }

    @Override
    public synchronized double getTime() {
        calls.add("get_time");
        return dayTime;
    }

    @Override
    public synchronized String getWeather() {
        calls.add("get_weather");
        return weather;
    }

    @Override
    public synchronized String getBiome() {
        calls.add("get_biome");
        return biome;
    }

    @Override
    public synchronized double getLight() {
        calls.add("get_light");
        return light;
    }

    @Override
    public synchronized String getPlotId() {
        calls.add("get_plot_id");
        return plotId;
    }

    @Override
    public synchronized void print(String text) {
        printed.add(text);
    }

    @Override
    public synchronized boolean castLine() {
        calls.add("cast_line");
        if (!hasRod || fishing) {
            return false;
        }
        fishing = true;
        return true;
    }

    @Override
    public synchronized boolean reelIn() {
        calls.add("reel_in");
        if (!fishing) {
            return false;
        }
        fishing = false;
        return true;
    }

    @Override
    public synchronized boolean isFishing() {
        calls.add("is_fishing");
        return fishing;
    }

    @Override
    public synchronized boolean isBobberBobbing() {
        calls.add("is_bobber_bobbing");
        return bobbing;
    }

    @Override
    public synchronized boolean didFishBite() {
        calls.add("did_fish_bite");
        return biting;
    }

    @Override
    public synchronized boolean isOpenWaterCast() {
        calls.add("is_open_water_cast");
        return openWaterCast;
    }

    @Override
    public synchronized double getRodDurability() {
        calls.add("get_rod_durability");
        return rodDurability;
    }

    @Override
    public synchronized boolean isAnvil() {
        calls.add("is_anvil");
        return anvil;
    }

    @Override
    public synchronized double getRepairCost() {
        calls.add("get_repair_cost");
        return repairCost;
    }

    @Override
    public synchronized boolean repairRod() {
        calls.add("repair_rod");
        return repairPossible;
    }

    /**
     * A synchronized snapshot of {@link #printed} - use this (not the raw {@code printed} field
     * directly) from a test that polls/reads it while a create_task task on another thread might
     * still be writing to it. Reading the plain field directly is fine everywhere else in this
     * suite, where the Interpreter under test has already fully finished running on the calling
     * thread before any assertion runs.
     */
    synchronized List<String> printedSnapshot() {
        return new ArrayList<>(printed);
    }

    @Override
    public synchronized void sleepTicks(double ticks) {
        calls.add("sleep_ticks:" + ticks);
    }
}
