package io.github.khayashi4337.micradrone.drone;

/**
 * ARGB palette + gradient rules for the IDE screen's bird's-eye plot views. Minecraft-free so the
 * color math is unit-testable; both the live view of the real farm ({@code LivePlotView}, which
 * feeds real crop AGE values in as {@code growth}) and the dry-run simulation view draw from this
 * one palette, so the two views read consistently.
 */
public final class PlotColorRules {
    public static final int UNTILLED = 0xFF9B7653;       // dry dirt
    public static final int FARMLAND_MOIST = 0xFF5C4033; // watered dark farmland
    public static final int FARMLAND_DRY = 0xFF7A5C3E;   // unwatered lighter farmland
    public static final int WHEAT_YOUNG = 0xFF5B7F3B;    // freshly planted green...
    public static final int WHEAT_MATURE = 0xFFE0C34E;   // ...ripening to golden
    public static final int CARROT_YOUNG = 0xFF4F7A3F;
    public static final int CARROT_MATURE = 0xFFE08A2E;  // carrot orange
    public static final int PUMPKIN_STEM_YOUNG = 0xFF3F7A44;
    public static final int PUMPKIN_STEM_GROWN = 0xFF4E9E52; // full-grown vine green
    public static final int PUMPKIN_FRUIT = 0xFFC46210;  // deep pumpkin orange
    public static final int GIANT_PUMPKIN = 0xFFE07818;  // brighter fused-patch orange
    public static final int ROTTEN_PUMPKIN = 0xFF6E7B5A; // sickly gray-green

    private PlotColorRules() {
    }

    public static int wheat(float growth) {
        return lerp(WHEAT_YOUNG, WHEAT_MATURE, growth);
    }

    public static int carrot(float growth) {
        return lerp(CARROT_YOUNG, CARROT_MATURE, growth);
    }

    public static int pumpkinStem(float growth) {
        return lerp(PUMPKIN_STEM_YOUNG, PUMPKIN_STEM_GROWN, growth);
    }

    public static int farmland(boolean moist) {
        return moist ? FARMLAND_MOIST : FARMLAND_DRY;
    }

    /** Per-channel linear ARGB interpolation; {@code t} outside [0,1] is clamped, not extrapolated. */
    public static int lerp(int fromArgb, int toArgb, float t) {
        float clamped = Math.min(1.0f, Math.max(0.0f, t));
        int a = lerpChannel(fromArgb >>> 24, toArgb >>> 24, clamped);
        int r = lerpChannel((fromArgb >> 16) & 0xFF, (toArgb >> 16) & 0xFF, clamped);
        int g = lerpChannel((fromArgb >> 8) & 0xFF, (toArgb >> 8) & 0xFF, clamped);
        int b = lerpChannel(fromArgb & 0xFF, toArgb & 0xFF, clamped);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerpChannel(int from, int to, float t) {
        return Math.round(from + (to - from) * t);
    }
}
