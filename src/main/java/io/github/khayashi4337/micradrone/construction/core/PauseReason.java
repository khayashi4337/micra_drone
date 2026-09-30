package io.github.khayashi4337.micradrone.construction.core;

/** Why a job waits. SITE_CHANGED: a position became unplaceable after the survey (F-3, E-SITE-CHANGED). NO_ROOM: a give has nowhere to go (F-7). */
public enum PauseReason {
    OWNER_OFFLINE, CHUNK_UNLOADED, MATERIALS_MISSING, SERVER_BUSY, RECOVERY_NEEDED, USER, SITE_CHANGED, NO_ROOM
}
