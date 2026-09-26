package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Decides whether the world still matches what a manifest expects. Only listed states count, volatile ones never do. */
public final class Conflicts {
    private static final String AIR_ID = BlockSpec.AIR.blockId();

    private Conflicts() {
    }

    public static Optional<Conflict> detect(IntPos pos, BlockSpec expected, ObservedBlock observed, Set<String> volatileProps) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(observed, "observed");
        Objects.requireNonNull(volatileProps, "volatileProps");
        BlockSpec actual = observed.block();
        // expecting air means the job never planned this cell, so whatever is there is not a conflict
        if (expected.blockId().equals(AIR_ID)) {
            return Optional.empty();
        }
        if (actual.blockId().equals(AIR_ID)) {
            return Optional.of(new Conflict(pos, expected, observed, ConflictKind.MISSING));
        }
        if (!actual.blockId().equals(expected.blockId())) {
            return Optional.of(new Conflict(pos, expected, observed, ConflictKind.PLAYER_MODIFIED));
        }
        for (Map.Entry<String, String> e : expected.properties().entrySet()) {
            if (volatileProps.contains(e.getKey())) {
                continue;
            }
            if (!e.getValue().equals(actual.get(e.getKey()))) {
                return Optional.of(new Conflict(pos, expected, observed, ConflictKind.PLAYER_MODIFIED));
            }
        }
        return Optional.empty();
    }
}
