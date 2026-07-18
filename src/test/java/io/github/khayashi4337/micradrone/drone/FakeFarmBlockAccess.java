package io.github.khayashi4337.micradrone.drone;

/** Minimal in-memory stand-in for {@link FarmBlockAccess}: one cell, no real blocks involved. */
final class FakeFarmBlockAccess implements FarmBlockAccess {
    private boolean tilled = false;
    private String plantedCrop = null;
    private boolean mature = false;

    void setMature(boolean mature) {
        this.mature = mature;
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
}
