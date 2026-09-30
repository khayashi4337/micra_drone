package io.github.khayashi4337.micradrone.construction.core;

import java.util.UUID;

/**
 * A job as the owner sees it (F-2(d)): {@code shownPause} is the reason of a PAUSED job, and SERVER_BUSY for a job
 * waiting in the queue or slowed by the server's budget, so a waiting job never looks stuck without a cause.
 */
public record JobStatus(String jobId, UUID owner, JobKind kind, JobState state, PauseReason shownPause, int cursor,
                        int total, int repairRound, int conflicts, int skipped, int unrepaired, String lastError,
                        String claimId, String dimension) {
}
