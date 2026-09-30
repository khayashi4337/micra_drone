package io.github.khayashi4337.micradrone.build.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class VolatilePropsTest {
    @Test
    void doorsIgnoreOpenAndPoweredAndUnknownNodesIgnoreNothing() {
        Function<String, Set<String>> v = VolatileProps.of(Map.of("door-1", "micra:door", "wall-n", "micra:wall"),
                BuildingParts.registry());
        assertEquals(Set.of("open", "powered"), v.apply("door-1"));
        assertEquals(Set.of(), v.apply("wall-n"));
        assertEquals(Set.of(), v.apply("site-prep"));
        assertEquals(Set.of(), v.apply("no-such-node"));
    }
}
