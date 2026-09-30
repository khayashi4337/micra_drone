package io.github.khayashi4337.micradrone.construction.core;

import java.util.UUID;

/** An approved job waiting for a running slot; the queue keeps arrival order (04 F-2). */
public record QueuedJob(String jobId, UUID owner) {
}
