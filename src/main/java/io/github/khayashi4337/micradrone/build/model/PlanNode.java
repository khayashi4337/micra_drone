package io.github.khayashi4337.micradrone.build.model;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One part instance. {@code parent} nests it (a wall in a structure); {@code type} is a registry id or a template id. */
public record PlanNode(String id, String type, String parent, Anchor anchor, Map<String, ParamValue> params,
                       Set<String> tags, String label) {
    public PlanNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(anchor, "anchor");
        params = SortedCopies.map(params);
        tags = SortedCopies.set(tags);
        label = Objects.requireNonNullElse(label, "");
    }
}
