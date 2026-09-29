package io.github.khayashi4337.micradrone.construction.core;

/** Why a job waits. SITE_CHANGED: a position became unplaceable after the survey (F-3, E-SITE-CHANGED). */
public enum PauseReason {
    OWNER_OFFLINE, CHUNK_UNLOADED, MATERIALS_MISSING, SERVER_BUSY, RECOVERY_NEEDED, USER, SITE_CHANGED
}
