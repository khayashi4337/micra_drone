package io.github.khayashi4337.micradrone.construction.core;

/** Where a construction job stands (design 01, section 8). VERIFIED is the job's state only, not the factory's. */
public enum JobState {
    PENDING_APPROVAL, QUEUED, RUNNING, PAUSED, VERIFYING, REPAIRING, ASSEMBLING, VERIFIED, PARTIAL, FAILED, CANCELLED,
    ROLLED_BACK;

    public boolean terminal() {
        return this == VERIFIED || this == PARTIAL || this == FAILED || this == CANCELLED || this == ROLLED_BACK;
    }
}
