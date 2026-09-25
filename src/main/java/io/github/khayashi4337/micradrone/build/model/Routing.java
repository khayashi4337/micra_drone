package io.github.khayashi4337.micradrone.build.model;

import java.util.List;

/** How a connection is routed: left to the Router (Auto) or through listed parts the author placed (Explicit). */
public sealed interface Routing {
    Auto AUTO = new Auto();

    record Auto() implements Routing {
    }

    record Explicit(List<String> viaNodeIds) implements Routing {
        public Explicit {
            viaNodeIds = List.copyOf(viaNodeIds);
        }
    }
}
