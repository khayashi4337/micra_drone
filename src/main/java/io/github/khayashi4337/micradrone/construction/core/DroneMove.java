package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.IntPos;

/** One show drone's assignment for one tick: which drone and the placed position it flies to (N-27). */
public record DroneMove(int drone, IntPos target) {
}
