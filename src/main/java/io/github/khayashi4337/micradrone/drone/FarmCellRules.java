package io.github.khayashi4337.micradrone.drone;

import java.util.Set;

/**
 * Pure till/plant/harvest success rules for one farm cell, kept Minecraft-free so the decisions
 * (as opposed to the block reads/writes that inform and carry them out) can be unit tested without
 * a real world. {@link LiveFarmBlockAccess} gathers {@link CellFacts} from real blocks and asks here
 * what they mean. Public (not package-private) because the dry-run simulator
 * ({@code drone.sim.SimulatedPlot}) applies these same rules to its in-memory plot, so the preview
 * can't drift from the real farm's behavior.
 */
public final class FarmCellRules {
    /** Every crop name the mod knows how to plant at all, regardless of whether a given plot has
     * unlocked it yet (see {@link UnlockShop} for the purchasable ones - "wheat" isn't purchasable,
     * every plot starts with it unlocked). */
    public static final Set<String> KNOWN_CROPS = Set.of("wheat", "carrot", "pumpkin");

    /** Ground/above-ground state for one farm cell, as plain facts (no Minecraft types). */
    public record CellFacts(boolean groundIsTillable, boolean groundIsFarmland, boolean aboveIsEmpty, boolean aboveIsMatureCrop) {}

    private FarmCellRules() {}

    /** till() succeeds on tillable ground (e.g. dirt/grass) with nothing occupying the cell above. */
    public static boolean canTill(CellFacts facts) {
        return facts.groundIsTillable() && facts.aboveIsEmpty();
    }

    /** plant(crop) succeeds on farmland with nothing occupying the cell above, for a known and unlocked crop. */
    public static boolean canPlant(String crop, boolean unlocked, CellFacts facts) {
        return KNOWN_CROPS.contains(crop) && unlocked && facts.groundIsFarmland() && facts.aboveIsEmpty();
    }

    /** harvest() (and can_harvest()) succeed only once the planted crop has reached its max growth stage. */
    public static boolean canHarvest(CellFacts facts) {
        return facts.aboveIsMatureCrop();
    }
}
