package io.github.khayashi4337.micradrone.lang;

import java.util.List;
import java.util.Map;

/**
 * The bridge between a construction script and the plan being built. Separate from {@link DroneApi} on purpose: the
 * farm commands and their pacing stay untouched, and a script is either a farm script or a construction script.
 * Values arrive as the interpreter holds them (numbers as Double, dicts as Map, lists as List).
 */
public interface PlanApi {
    void site(String dimension, int x, int y, int z, String facing, int[] bounds, String terrainDigest, String claimId);

    void style(String role, String material);

    void mood(String tag);

    void part(String id, String type, String parent, PlanAnchorArgs anchor, Map<String, Object> params, List<String> tags, String label);

    void updateParams(String id, Map<String, Object> params);

    void relocate(String id, PlanAnchorArgs anchor);

    void removePart(String id);

    /**
     * {@code via} is null for automatic routing and a (possibly empty) list of part ids for explicit routing;
     * {@code constraints} is null when the script gave none.
     */
    void connect(String id, String from, String to, String kind, List<String> via, Map<String, Object> constraints);

    void disconnect(String id);

    void logistics(List<Object> docks, List<Object> routes, List<Object> flows);

    void print(String text);
}
