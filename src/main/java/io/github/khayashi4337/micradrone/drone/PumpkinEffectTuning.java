package io.github.khayashi4337.micradrone.drone;

/**
 * The numbers behind the pumpkin effects (see PumpkinEffects), kept free of Minecraft types so they
 * can be unit tested: how a hand-broken giant pumpkin's collapse ripples outward from the broken
 * cell, and how the size-scaled sounds get deeper the bigger the pumpkin is.
 */
final class PumpkinEffectTuning {
    /** Ticks between one ring of cells crumbling and the next, counted outward from the broken cell. */
    static final int COLLAPSE_RING_DELAY_TICKS = 2;
    /** The smallest thing that fuses is a 2x2 - that's what the pitch scale starts from. */
    static final int SMALLEST_FUSED_SIDE = 2;
    /** Pitch of a size-scaled sound for the smallest fused pumpkin. */
    static final float PITCH_AT_SMALLEST = 1.1f;
    /** How much deeper each extra cell of side length makes it. */
    static final float PITCH_STEP_PER_SIDE = 0.15f;
    /** Floor: from about 6x6 up everything sounds equally huge. */
    static final float PITCH_MIN = 0.5f;

    private PumpkinEffectTuning() {
    }

    /**
     * Which ring around the broken cell a cell is on (Chebyshev distance), so the cells the same
     * number of steps away crumble together and the collapse spreads as a square wave.
     */
    static int collapseRing(int brokenX, int brokenZ, int x, int z) {
        return Math.max(Math.abs(x - brokenX), Math.abs(z - brokenZ));
    }

    /** Delay before a cell on {@code ring} crumbles; ring 0 is the broken cell itself, already gone. */
    static int collapseDelayTicks(int ring) {
        return ring * COLLAPSE_RING_DELAY_TICKS;
    }

    /** Deeper for bigger pumpkins, never below {@link #PITCH_MIN}. */
    static float pitchForSide(int side) {
        float pitch = PITCH_AT_SMALLEST - PITCH_STEP_PER_SIDE * (side - SMALLEST_FUSED_SIDE);
        return Math.max(PITCH_MIN, Math.min(PITCH_AT_SMALLEST, pitch));
    }
}
