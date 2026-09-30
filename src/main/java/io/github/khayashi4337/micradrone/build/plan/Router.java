package io.github.khayashi4337.micradrone.build.plan;

import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import java.util.Optional;

/** Finds the intermediate parts (shafts, belts, chutes) for an automatically routed connection. The real one arrives in P11. */
@FunctionalInterface
public interface Router {
    Router NONE = (plan, connection) -> Optional.empty();

    Optional<RoutedConnection> route(SemanticPlan plan, Connection connection);
}
