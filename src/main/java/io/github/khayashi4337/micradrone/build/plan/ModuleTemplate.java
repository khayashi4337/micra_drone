package io.github.khayashi4337.micradrone.build.plan;

import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.CanonicalJson;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Hashing;
import io.github.khayashi4337.micradrone.build.model.PlanJson;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.PartCategory;
import io.github.khayashi4337.micradrone.build.parts.PartTypeJson;
import io.github.khayashi4337.micradrone.build.parts.PortSpec;
import io.github.khayashi4337.micradrone.build.parts.VersionRange;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * A verified fragment of a plan (a press stand, a power room, ...). {@code hash()} covers the structure only, so
 * a bundled template is recognised by id and hash while stats and verification records may be refreshed.
 */
public record ModuleTemplate(int schemaVersion, String id, String displayNameKey, PartCategory category,
                             VersionRange requires, Box footprint, List<PortSpec> ports, List<PlanNode> nodes,
                             List<Connection> internal, TemplateStats stats, Verification verification, Set<String> tags) {
    // Keys of the tree the hash is computed from. They are hashed, so renaming one changes every template's hash.
    private static final String KEY_SCHEMA_VERSION = "schemaVersion";
    private static final String KEY_ID = "id";
    private static final String KEY_DISPLAY_NAME_KEY = "displayNameKey";
    private static final String KEY_CATEGORY = "category";
    private static final String KEY_REQUIRES = "requires";
    private static final String KEY_FOOTPRINT = "footprint";
    private static final String KEY_PORTS = "ports";
    private static final String KEY_NODES = "nodes";
    private static final String KEY_INTERNAL = "internal";
    private static final String KEY_TAGS = "tags";

    public ModuleTemplate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(category, "category");
        requires = Objects.requireNonNullElse(requires, VersionRange.ALWAYS);
        ports = List.copyOf(Objects.requireNonNullElse(ports, List.of()));
        nodes = List.copyOf(Objects.requireNonNullElse(nodes, List.of()));
        internal = List.copyOf(Objects.requireNonNullElse(internal, List.of()));
        tags = Collections.unmodifiableSortedSet(new TreeSet<>(Objects.requireNonNullElse(tags, Set.of())));
    }

    public Optional<PortSpec> port(String name) {
        for (PortSpec p : ports) {
            if (p.name().equals(name)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    /** SHA-256 of the canonical structure: ports, nodes and internal connections in id order; no stats, no verification. */
    public String hash() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_SCHEMA_VERSION, schemaVersion);
        m.put(KEY_ID, id);
        m.put(KEY_DISPLAY_NAME_KEY, displayNameKey);
        m.put(KEY_CATEGORY, category.name());
        m.put(KEY_REQUIRES, PartTypeJson.requiresTree(requires));
        m.put(KEY_FOOTPRINT, footprint == null ? null : PlanJson.boxTree(footprint));
        m.put(KEY_PORTS, sortedTrees(ports, PortSpec::name, PartTypeJson::portTree));
        m.put(KEY_NODES, sortedTrees(nodes, PlanNode::id, PlanJson::nodeToTree));
        m.put(KEY_INTERNAL, sortedTrees(internal, Connection::id, PlanJson::connectionToTree));
        m.put(KEY_TAGS, new ArrayList<>(tags));
        return Hashing.sha256Hex(CanonicalJson.write(m));
    }

    /** The trees of the items in the dictionary order of their keys, so the input order cannot change the hash. */
    private static <T> List<Object> sortedTrees(List<T> items, Function<T, String> key, Function<T, ?> toTree) {
        List<T> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparing(key));
        List<Object> trees = new ArrayList<>();
        for (T item : sorted) {
            trees.add(toTree.apply(item));
        }
        return trees;
    }
}
