package io.github.khayashi4337.micradrone.construction.core;

/** How many placements one job may do this tick (04 F-2). */
public record Allowance(String jobId, int placements) {
}
