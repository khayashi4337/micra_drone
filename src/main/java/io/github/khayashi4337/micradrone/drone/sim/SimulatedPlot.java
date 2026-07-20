package io.github.khayashi4337.micradrone.drone.sim;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import io.github.khayashi4337.micradrone.drone.FarmCellRules;

/**
 * In-memory model of one farm plot for dry-run simulation: a worldSize x worldSize grid of cells
 * (tilled flag, planted crop, maturity, rot) plus the drone's position and per-crop points.
 * Success/failure of till/plant/harvest goes through the exact same {@link FarmCellRules} the real
 * farm uses, so the preview can't disagree with the real farm about what's allowed.
 *
 * <p>Deliberate simplifications versus the real farm (documented for the GUI to state too):
 * <ul>
 *   <li>Time is counted in "steps" (one per DroneApi builtin call - see {@link SimDroneApi}), not
 *       game ticks: a planted crop matures {@link #MATURE_AFTER_STEPS} steps after planting. This
 *       replaces real-time growth + the plot's growth boost, and makes can_harvest()-polling
 *       loops advance toward maturity instead of spinning forever.</li>
 *   <li>Pumpkins roll the real {@link #PUMPKIN_ROT_CHANCE} at planting time (visible only once
 *       mature), from a fixed-seed {@link Random} so every replay of the same script is
 *       identical. Harvesting a rotten pumpkin succeeds but awards nothing, matching the real
 *       farm.</li>
 *   <li>Every crop counts as unlocked (the editor is a sandbox), and giant-pumpkin fusion is not
 *       simulated.</li>
 * </ul>
 */
final class SimulatedPlot {
    /** A planted crop is mature this many steps after plant() succeeded. */
    static final int MATURE_AFTER_STEPS = 10;
    /** Matches LiveFarmBlockAccess#PUMPKIN_ROT_CHANCE. */
    static final float PUMPKIN_ROT_CHANCE = 0.2f;
    /** Matches LiveFarmBlockAccess#POINTS_PER_HARVEST. */
    static final long POINTS_PER_HARVEST = 1;

    private final int size;
    private final boolean[] tilled;
    private final String[] crop;
    private final long[] matureAtStep;
    private final boolean[] rotten;
    private final Map<String, Long> pointsByCrop = new HashMap<>();
    private final Random rotRoll = new Random(0);
    private int droneX = 0;
    private int droneY = 0;
    private long step = 0;

    SimulatedPlot(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("plot size must be >= 1, got " + size);
        }
        this.size = size;
        this.tilled = new boolean[size * size];
        this.crop = new String[size * size];
        this.matureAtStep = new long[size * size];
        this.rotten = new boolean[size * size];
    }

    int size() {
        return size;
    }

    int droneX() {
        return droneX;
    }

    int droneY() {
        return droneY;
    }

    /** Called by {@link SimDroneApi} once per builtin call - the simulation's clock. */
    void advanceStep() {
        step++;
    }

    boolean tryMove(int dx, int dy) {
        int nx = droneX + dx;
        int ny = droneY + dy;
        if (nx < 0 || nx >= size || ny < 0 || ny >= size) {
            return false;
        }
        droneX = nx;
        droneY = ny;
        return true;
    }

    boolean tryTill() {
        if (!FarmCellRules.canTill(factsAtDrone())) {
            return false;
        }
        tilled[cellIndex()] = true;
        return true;
    }

    boolean tryPlant(String cropName) {
        if (!FarmCellRules.canPlant(cropName, true, factsAtDrone())) {
            return false;
        }
        int i = cellIndex();
        crop[i] = cropName;
        matureAtStep[i] = step + MATURE_AFTER_STEPS;
        rotten[i] = "pumpkin".equals(cropName) && rotRoll.nextFloat() < PUMPKIN_ROT_CHANCE;
        return true;
    }

    boolean tryHarvest() {
        if (!FarmCellRules.canHarvest(factsAtDrone())) {
            return false;
        }
        int i = cellIndex();
        if (!rotten[i]) {
            pointsByCrop.merge(crop[i], POINTS_PER_HARVEST, Long::sum);
        }
        crop[i] = null;
        rotten[i] = false;
        return true;
    }

    boolean canHarvest() {
        return FarmCellRules.canHarvest(factsAtDrone());
    }

    /** Rot is only observable once the pumpkin has actually grown, mirroring the real farm. */
    boolean isRotten() {
        int i = cellIndex();
        return crop[i] != null && isMature(i) && rotten[i];
    }

    double totalPoints() {
        return pointsByCrop.values().stream().mapToLong(Long::longValue).sum();
    }

    double points(String cropName) {
        return pointsByCrop.getOrDefault(cropName, 0L);
    }

    byte[] snapshotCells() {
        byte[] cells = new byte[size * size];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = encodeCell(i);
        }
        return cells;
    }

    private byte encodeCell(int i) {
        if (crop[i] == null) {
            return tilled[i] ? SimFrame.CELL_TILLED : SimFrame.CELL_UNTILLED;
        }
        boolean mature = isMature(i);
        return switch (crop[i]) {
            case "wheat" -> mature ? SimFrame.CELL_WHEAT_MATURE : SimFrame.CELL_WHEAT_GROWING;
            case "carrot" -> mature ? SimFrame.CELL_CARROT_MATURE : SimFrame.CELL_CARROT_GROWING;
            case "pumpkin" -> !mature ? SimFrame.CELL_PUMPKIN_GROWING
                    : rotten[i] ? SimFrame.CELL_PUMPKIN_ROTTEN : SimFrame.CELL_PUMPKIN_MATURE;
            default -> throw new IllegalStateException("unencodable crop '" + crop[i] + "'");
        };
    }

    private boolean isMature(int i) {
        return step >= matureAtStep[i];
    }

    private FarmCellRules.CellFacts factsAtDrone() {
        int i = cellIndex();
        boolean hasCrop = crop[i] != null;
        return new FarmCellRules.CellFacts(!tilled[i], tilled[i], !hasCrop, hasCrop && isMature(i));
    }

    private int cellIndex() {
        return droneY * size + droneX;
    }
}
