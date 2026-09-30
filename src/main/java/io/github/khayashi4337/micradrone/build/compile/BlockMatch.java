package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Whether an observed block satisfies an expected one: the same block id, and every state the expectation lists (minus
 * the ignored, volatile ones) equal. States the expectation does not list are not compared: the pure core cannot tell a
 * default state from a set one, and neighbour-driven states (stair shape, pane connections) are not the plan's choice.
 */
public final class BlockMatch {
    private BlockMatch() {
    }

    public static boolean satisfies(BlockSpec observed, BlockSpec expected, Set<String> ignoredProps) {
        if (!observed.blockId().equals(expected.blockId())) {
            return false;
        }
        for (Map.Entry<String, String> e : expected.properties().entrySet()) {
            if (!ignoredProps.contains(e.getKey()) && !e.getValue().equals(observed.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * VerifyMode.EXACT (design 05, 1.1.1): the same id and exactly the same states on both sides, the ignored (volatile)
     * ones aside. A state the observation has but the plan did not list fails, unlike {@link #satisfies}.
     */
    public static boolean exact(BlockSpec observed, BlockSpec expected, Set<String> ignoredProps) {
        if (!observed.blockId().equals(expected.blockId())) {
            return false;
        }
        Set<String> keys = new TreeSet<>(observed.properties().keySet());
        keys.addAll(expected.properties().keySet());
        keys.removeAll(ignoredProps);
        for (String k : keys) {
            if (!Objects.equals(observed.get(k), expected.get(k))) {
                return false;
            }
        }
        return true;
    }
}
