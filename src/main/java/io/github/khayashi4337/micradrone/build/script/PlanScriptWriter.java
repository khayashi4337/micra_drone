package io.github.khayashi4337.micradrone.build.script;

public final class PlanScriptWriter {
    /**
     * Same value as DroneControllerBlockEntity.MAX_SCRIPT_CHARS, repeated here so this Minecraft-free core does not depend
     * on that class; a test compares the two source texts.
     */
    public static final int MAX_SCRIPT_CHARS = 10_000;

    private PlanScriptWriter() {
    }
}
