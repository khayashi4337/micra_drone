package io.github.khayashi4337.micradrone.build.model;

/** A port on a node, e.g. {@code press-1 / power_in}. */
public record PortRef(String nodeId, String port) {
}
