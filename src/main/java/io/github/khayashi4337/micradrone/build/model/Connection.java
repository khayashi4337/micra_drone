package io.github.khayashi4337.micradrone.build.model;

import java.util.Objects;

/** A link between two ports. Written by ports, never by coordinates; intermediate parts are the Router's job. */
public record Connection(String id, PortRef from, PortRef to, ConnKind kind, Routing routing, Constraints constraints) {
    public Connection {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(kind, "kind");
        routing = Objects.requireNonNullElse(routing, Routing.AUTO);
        constraints = Objects.requireNonNullElse(constraints, Constraints.NONE);
    }
}
