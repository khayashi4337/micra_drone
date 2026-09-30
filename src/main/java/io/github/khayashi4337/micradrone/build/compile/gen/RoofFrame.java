package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.LocalPos;

/**
 * Maps a roof's own axes to the local frame: {@code a} runs across the slope, {@code b} along the ridge. With the
 * ridge along w the slope runs across u (a=u, b=w); with the ridge along u it runs across w (a=w, b=u).
 */
record RoofFrame(boolean ridgeAlongW, int a0, int a1, int b0, int b1) {
    LocalPos pos(int a, int v, int b) {
        return ridgeAlongW ? new LocalPos(a, v, b) : new LocalPos(b, v, a);
    }

    /** Back direction of a stair on the low-a side: toward the ridge, i.e. toward larger a. */
    Facing towardRidgeFromLow() {
        return ridgeAlongW ? Facing.EAST : Facing.NORTH;
    }

    Facing towardRidgeFromHigh() {
        return towardRidgeFromLow().opposite();
    }
}
