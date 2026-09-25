package io.github.khayashi4337.micradrone.build.parts;

import io.github.khayashi4337.micradrone.build.model.Box;

/** How far a part acts on the world (D-24). Parts that break blocks, move liquids or fire must declare it. */
public record EffectSpec(EffectKind kind, Box reachLocal) {
    public static final EffectSpec NONE = new EffectSpec(EffectKind.NONE, null);
}
