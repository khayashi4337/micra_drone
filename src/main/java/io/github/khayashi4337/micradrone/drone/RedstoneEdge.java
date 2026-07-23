package io.github.khayashi4337.micradrone.drone;

/**
 * Rising/falling edge detection for the controller's redstone Run/Stop (issue #7): lever ON runs
 * the slotted scroll, lever OFF stops it, and holding a level steady does nothing. Starts from
 * "unpowered", so a chunk that loads with its lever already ON neither auto-starts nor emits a
 * spurious falling edge on the first OFF... it simply syncs on the first actual change.
 * Minecraft-free so the edge rules are unit-testable.
 */
final class RedstoneEdge {
    enum Edge { RISING, FALLING, NONE }

    private boolean lastPowered = false;

    Edge update(boolean powered) {
        if (powered == lastPowered) {
            return Edge.NONE;
        }
        lastPowered = powered;
        return powered ? Edge.RISING : Edge.FALLING;
    }
}
