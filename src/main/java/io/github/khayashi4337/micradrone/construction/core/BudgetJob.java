package io.github.khayashi4337.micradrone.construction.core;

import java.util.UUID;

/**
 * One running job as the budget sees it (04 F-2).
 *
 * @param jobId         the job's id
 * @param owner         the job owner's uuid (at most one running job per owner, F-5)
 * @param fastPhase     whether the phase at the current cursor is SITE_PREP through ENVELOPE, which places without
 *                      drones at the fast per-tick rate; the drone-shown phases place at the drones' pace
 * @param droneCount    the drones the job flies (from {@link ConstructionBudget#droneCount})
 * @param lastDroneTick the tick the drones last placed for this job
 * @param admittedOrder the order the job entered the running set; the per-tick rotation starts from it
 */
public record BudgetJob(String jobId, UUID owner, boolean fastPhase, int droneCount, long lastDroneTick,
                        long admittedOrder) {
}
