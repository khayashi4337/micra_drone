package io.github.khayashi4337.micradrone.build.model;

import java.util.List;
import java.util.Objects;

/** Docks, flight routes and cargo flows. Part of the plan, so it travels with approval and hashing. */
public record LogisticsPlan(List<Dock> docks, List<Route> routes, List<CargoFlow> flows) {
    public LogisticsPlan {
        docks = List.copyOf(Objects.requireNonNullElse(docks, List.of()));
        routes = List.copyOf(Objects.requireNonNullElse(routes, List.of()));
        flows = List.copyOf(Objects.requireNonNullElse(flows, List.of()));
    }

    public record Dock(String id, Box padBox, Box clearanceBox, Facing approach, List<PortRef> linkedPorts,
                       List<String> dockingConnectorNodeIds) {
        public Dock {
            linkedPorts = List.copyOf(Objects.requireNonNullElse(linkedPorts, List.of()));
            dockingConnectorNodeIds = List.copyOf(Objects.requireNonNullElse(dockingConnectorNodeIds, List.of()));
        }
    }

    public record Route(String id, String fromDock, String toDock, List<LocalPos> waypoints, String airshipTemplateId) {
        public Route {
            waypoints = List.copyOf(Objects.requireNonNullElse(waypoints, List.of()));
        }
    }

    /**
     * How much of an item moves between two docks per minute. The rate is a finite number (the content hash refuses
     * NaN and the infinities, so a plan holding one could be built but never hashed) and never negative zero
     * (see {@link Zeros}).
     */
    public record CargoFlow(String itemId, double perMin, String fromDock, String toDock) {
        public CargoFlow {
            if (!Double.isFinite(perMin)) {
                throw new IllegalArgumentException("perMin must be a finite number: " + perMin);
            }
            perMin = Zeros.positive(perMin);
        }
    }
}
