package io.github.khayashi4337.micradrone.construction.core;

/** A repair round's own program with its own cursor: it survives a pause and is picked up where it stopped. */
public record RepairQueue(JobProgram program, int cursor) {
}
