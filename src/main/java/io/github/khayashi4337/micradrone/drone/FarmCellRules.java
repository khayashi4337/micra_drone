package io.github.khayashi4337.micradrone.drone;

/**
 * Pure till/plant/harvest success rules for one farm cell, kept Minecraft-free so the decisions
 * (as opposed to the block reads/writes that inform and carry them out) can be unit tested without
 * a real world. {@link LiveFarmBlockAccess} gathers {@link CellFacts} from real blocks and asks here
 * what they mean.
 */
final class FarmCellRules {
    /** Only crop currently supported (see Phase 1 design doc: additional crops are Phase 3). */
    static final String SUPPORTED_CROP = "wheat";

    /** Ground/above-ground state for one farm cell, as plain facts (no Minecraft types). */
    record CellFacts(boolean groundIsTillable, boolean groundIsFarmland, boolean aboveIsEmpty, boolean aboveIsMatureCrop) {}

    private FarmCellRules() {}

    /** till() succeeds on tillable ground (e.g. dirt/grass) with nothing occupying the cell above. */
    static boolean canTill(CellFacts facts) {
        return facts.groundIsTillable() && facts.aboveIsEmpty();
    }

    /** plant(crop) succeeds on farmland with nothing occupying the cell above, for a supported crop. */
    static boolean canPlant(String crop, CellFacts facts) {
        return SUPPORTED_CROP.equals(crop) && facts.groundIsFarmland() && facts.aboveIsEmpty();
    }

    /** harvest() (and can_harvest()) succeed only once the planted crop has reached its max growth stage. */
    static boolean canHarvest(CellFacts facts) {
        return facts.aboveIsMatureCrop();
    }
}
