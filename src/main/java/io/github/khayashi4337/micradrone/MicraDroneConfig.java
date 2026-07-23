package io.github.khayashi4337.micradrone;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side mod settings (registered in {@link MicraDrone}'s constructor). Kept to the few
 * knobs 林さん actually asked for - this mod prefers vanilla-block conventions over options.
 */
public final class MicraDroneConfig {
    public static final ModConfigSpec SPEC;
    /** Auto-place a shulker box full of sample script scrolls when a plot is first claimed (issue #7). */
    public static final ModConfigSpec.BooleanValue AUTO_PLACE_SAMPLE_LIBRARY;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        AUTO_PLACE_SAMPLE_LIBRARY = builder
                .comment("When a controller first pairs with a Corner Marker, automatically place a shulker box",
                        "full of sample script scrolls on a free corner of the plot square.")
                .define("autoPlaceSampleLibrary", true);
        SPEC = builder.build();
    }

    private MicraDroneConfig() {
    }
}
