package io.github.khayashi4337.micradrone.construction.core;

/**
 * The owner's (or an operator's) answer to a job that is PAUSED(RECOVERY_NEEDED) (04 F-2): ADOPT and DISCARD decide the
 * runs of the write-ahead log the evidence could not; FAIL ends the job for a manual clean-up; REPAIR ends it and
 * starts a REPAIR job that re-derives everything from the world.
 */
public enum RecoveryChoice {
    ADOPT, DISCARD, REPAIR, FAIL
}
