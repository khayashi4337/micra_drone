package io.github.khayashi4337.micradrone.build.parts;

import io.github.khayashi4337.micradrone.build.model.Box;
import java.util.Objects;

/** How far a part acts on the world (D-24). Parts that break blocks, move liquids or fire must declare it. */
public record EffectSpec(EffectKind kind, Box reachLocal) {
    public static final EffectSpec NONE = new EffectSpec(EffectKind.NONE, null);

    /** {@code reachLocal} may be null only for {@link EffectKind#NONE}, which reaches nowhere. */
    public EffectSpec {
        Objects.requireNonNull(kind, "kind");
        if (kind != EffectKind.NONE && reachLocal == null) {
            throw new IllegalArgumentException("effect " + kind + " needs a reachLocal box");
        }
    }
}
