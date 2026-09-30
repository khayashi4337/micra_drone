package io.github.khayashi4337.micradrone.construction.core;

/** How a worker job ended, delivered on the main thread. */
public sealed interface WorkResult<T> {
    record Done<T>(T value) implements WorkResult<T> {
    }

    record Failed<T>(Throwable error) implements WorkResult<T> {
    }

    record Cancelled<T>() implements WorkResult<T> {
    }
}
