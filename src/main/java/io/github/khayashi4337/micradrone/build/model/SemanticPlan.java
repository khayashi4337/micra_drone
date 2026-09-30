package io.github.khayashi4337.micradrone.build.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The design data: the source of truth (D-2). Scripts are a round-trippable view of it. {@code nodes} is in
 * insertion order and a parent always comes before its children; {@code contentHash} ignores the order. A node that
 * was relocated onto a wall added AFTER it rests on a wall listed after it, so the stored order is not always one a
 * plan can be rebuilt in: whatever rebuilds a plan from its nodes (the script writer, {@code PlanPatcher.normalize})
 * uses {@link NodeOrder}, which puts each node after its parent and after the wall it rests on.
 */
public record SemanticPlan(int schemaVersion, String planId, int revision, Integer parentRevision, Site site,
                           StyleSpec style, List<PlanNode> nodes, List<Connection> connections,
                           LogisticsPlan logistics, Provenance provenance) {
    public static final int SCHEMA_VERSION = 1;

    public SemanticPlan {
        nodes = List.copyOf(Objects.requireNonNullElse(nodes, List.of()));
        connections = List.copyOf(Objects.requireNonNullElse(connections, List.of()));
        style = Objects.requireNonNullElse(style, StyleSpec.EMPTY);
        provenance = Objects.requireNonNullElse(provenance, Provenance.NONE);
    }

    public static SemanticPlan empty(String planId) {
        return new SemanticPlan(SCHEMA_VERSION, planId, 0, null, null, StyleSpec.EMPTY, List.of(), List.of(), null,
                Provenance.NONE);
    }

    public Optional<PlanNode> node(String id) {
        for (PlanNode n : nodes) {
            if (n.id().equals(id)) {
                return Optional.of(n);
            }
        }
        return Optional.empty();
    }

    /** SHA-256 of the canonical content (everything except planId, revision, parentRevision, provenance). */
    public String contentHash() {
        return PlanJson.contentHash(this);
    }
}
