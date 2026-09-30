package io.github.khayashi4337.micradrone.build.plan;

import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import java.util.List;

/**
 * A plan with modules replaced by their parts and connections resolved. Rebuilt by the server, never trusted from the client.
 * The order is fixed by the source plan, so the same plan and templates always expand to the same lists:
 * <ul>
 * <li>{@code primitiveNodes}: the plan's nodes in plan order, each module instance replaced in place by its template's
 * nodes (in the template's order);</li>
 * <li>{@code routed}: the plan's connections in plan order, then the internal connections of each template in the
 * order of the instances in the plan (and in the template's order within one);</li>
 * <li>{@code templateHashes}: the hashes of the templates used, once each, in dictionary order.</li>
 * </ul>
 */
public record ExpandedPlan(SemanticPlan source, List<PlanNode> primitiveNodes, List<RoutedConnection> routed,
                           List<String> templateHashes) {
    public ExpandedPlan {
        primitiveNodes = List.copyOf(primitiveNodes);
        routed = List.copyOf(routed);
        templateHashes = List.copyOf(templateHashes);
    }
}
