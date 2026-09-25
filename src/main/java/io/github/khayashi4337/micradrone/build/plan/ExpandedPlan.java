package io.github.khayashi4337.micradrone.build.plan;

import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import java.util.List;

/** A plan with modules replaced by their parts and connections resolved. Rebuilt by the server, never trusted from the client. */
public record ExpandedPlan(SemanticPlan source, List<PlanNode> primitiveNodes, List<RoutedConnection> routed,
                           List<String> templateHashes) {
    public ExpandedPlan {
        primitiveNodes = List.copyOf(primitiveNodes);
        routed = List.copyOf(routed);
        templateHashes = List.copyOf(templateHashes);
    }
}
