package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Decides whether the world still matches what a manifest expects. Only listed states count, volatile ones never do. */
public final class Conflicts {
    private Conflicts() {
    }

    /**
     * Compares one expected cell with what the world holds, in this order:
     * <ol>
     *   <li>expected non-air but observed air -&gt; {@link ConflictKind#MISSING}</li>
     *   <li>different block ids -&gt; {@link ConflictKind#PLAYER_MODIFIED}; this includes expected air with a
     *   non-air observed block, because something the job did not place stands where the job planned air</li>
     *   <li>same id but a non-volatile expected property differs from the observed -&gt;
     *   {@link ConflictKind#PLAYER_MODIFIED}</li>
     *   <li>otherwise no conflict (expected air and observed air agree: same id, no properties)</li>
     * </ol>
     * The expected-air case is deliberate: a false conflict only pauses work, while a silent overwrite of a
     * player's block destroys it.
     */
    public static Optional<Conflict> detect(IntPos pos, BlockSpec expected, ObservedBlock observed, Set<String> volatileProps) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(observed, "observed");
        Objects.requireNonNull(volatileProps, "volatileProps");
        BlockSpec actual = observed.block();
        if (!expected.isAir() && actual.isAir()) {
            return Optional.of(new Conflict(pos, expected, observed, ConflictKind.MISSING));
        }
        if (!BlockMatch.satisfies(actual, expected, volatileProps)) {
            return Optional.of(new Conflict(pos, expected, observed, ConflictKind.PLAYER_MODIFIED));
        }
        return Optional.empty();
    }
}
