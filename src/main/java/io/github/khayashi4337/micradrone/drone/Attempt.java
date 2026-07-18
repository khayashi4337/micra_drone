package io.github.khayashi4337.micradrone.drone;

/** Phase A result for a paced action: success/failure decided immediately; apply() performs the Phase B mutation. */
record Attempt(boolean succeeded, Runnable apply) {
    static Attempt failure() {
        return new Attempt(false, () -> {});
    }
}
