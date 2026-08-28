package io.github.khayashi4337.micradrone.chat;

/**
 * Identifies a chat history file: the controller's dimension plus its block position. Dimension
 * is a plain String (e.g. "minecraft:the_nether") rather than Minecraft's ResourceKey&lt;Level&gt;
 * so this stays off the Minecraft classpath - the same reason RegionSelectionState uses plain
 * ints instead of BlockPos. Without the dimension, two controllers at the same coordinates in
 * different dimensions would collide onto one history file (Codex review finding).
 */
public record ControllerKey(String dimension, int x, int y, int z) {
    /** A filesystem-safe, collision-free name for this key's history file. */
    public String storageFileName() {
        return dimension.replace(':', '_') + "_" + x + "_" + y + "_" + z + ".chat";
    }
}
