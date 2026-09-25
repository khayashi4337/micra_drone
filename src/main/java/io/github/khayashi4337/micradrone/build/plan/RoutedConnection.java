package io.github.khayashi4337.micradrone.build.plan;

import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import java.util.List;

/** A connection after resolution: the parts a router added for it and the positions it runs through. */
public record RoutedConnection(String connectionId, List<PlanNode> intermediateNodes, List<LocalPos> path) {
    public RoutedConnection {
        intermediateNodes = List.copyOf(intermediateNodes);
        path = List.copyOf(path);
    }
}
