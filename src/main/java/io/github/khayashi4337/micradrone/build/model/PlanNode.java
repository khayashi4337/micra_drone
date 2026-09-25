package io.github.khayashi4337.micradrone.build.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** One part instance. {@code parent} nests it (a wall in a structure); {@code type} is a registry id or a template id. */
public record PlanNode(String id, String type, String parent, Anchor anchor, Map<String, ParamValue> params,
                       Set<String> tags, String label) {
    public PlanNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(anchor, "anchor");
        params = Collections.unmodifiableSortedMap(new TreeMap<>(Objects.requireNonNullElse(params, Map.of())));
        tags = Collections.unmodifiableSortedSet(new TreeSet<>(Objects.requireNonNullElse(tags, Set.of())));
        label = Objects.requireNonNullElse(label, "");
    }
}
