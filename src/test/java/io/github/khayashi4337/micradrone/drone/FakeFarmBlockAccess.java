package io.github.khayashi4337.micradrone.drone;

/** Minimal in-memory stand-in for {@link FarmBlockAccess}: one cell, no real blocks involved. */
final class FakeFarmBlockAccess implements FarmBlockAccess {
    private boolean tilled = false;
    private String plantedCrop = null;
    private boolean mature = false;
    private boolean rotten = false;
    private int giantPumpkinSide = 0;

    void setMature(boolean mature) {
        this.mature = mature;
    }

    void setRotten(boolean rotten) {
        this.rotten = rotten;
    }

    boolean isTilled() {
        return tilled;
    }

    String plantedCrop() {
        return plantedCrop;
    }

    @Override
    public Attempt attemptTill() {
        if (tilled) {
            return Attempt.failure();
        }
        return new Attempt(true, () -> tilled = true);
    }

    @Override
    public Attempt attemptPlant(String crop) {
        if (!tilled || plantedCrop != null || !"wheat".equals(crop)) {
            return Attempt.failure();
        }
        return new Attempt(true, () -> plantedCrop = crop);
    }

    @Override
    public Attempt attemptHarvest() {
        if (plantedCrop == null || !mature) {
            return Attempt.failure();
        }
        return new Attempt(true, () -> {
            plantedCrop = null;
            mature = false;
        });
    }

    @Override
    public boolean canHarvest() {
        return plantedCrop != null && mature;
    }

    @Override
    public boolean isRotten() {
        return rotten;
    }

    @Override
    public int giantPumpkinSide() {
        return giantPumpkinSide;
    }

    // ---- perception (issue #10) ----
    // Fixed readings except for the ground, which tracks this cell's real till state so a test can
    // tell an "I looked and saw farmland" branch from a hardcoded answer.

    @Override
    public String groundBlockName() {
        return tilled ? "farmland" : "dirt";
    }

    @Override
    public String blockAboveName() {
        return plantedCrop == null ? "air" : plantedCrop;
    }

    @Override
    public long dayTime() {
        return 6000; // noon
    }

    @Override
    public String weather() {
        return "clear";
    }

    @Override
    public String biomeName() {
        return "plains";
    }

    @Override
    public int lightLevel() {
        return 15;
    }

    @Override
    public String plotId() {
        return "test-plot";
    }
}
