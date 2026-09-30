package io.github.khayashi4337.micradrone.build.verify;

import io.github.khayashi4337.micradrone.build.parts.PartType;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Node id to the part's volatile states (design 01, section 3): what L7, MODIFY and repair never compare or reset. */
public final class VolatileProps {
    private VolatileProps() {
    }

    public static Function<String, Set<String>> of(Map<String, String> nodeTypes, PartTypeRegistry registry) {
        Map<String, Set<String>> byNode = new HashMap<>();
        nodeTypes.forEach((node, type) -> byNode.put(node, registry.find(type).map(PartType::volatileProps).orElse(Set.of())));
        return node -> byNode.getOrDefault(node, Set.of());
    }
}
