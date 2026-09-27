package io.github.khayashi4337.micradrone.lang;

import java.util.List;
import java.util.Map;

/**
 * The bridge between a construction script and the plan being built. Separate from {@link DroneApi} on purpose: the
 * farm commands and their pacing stay untouched, and a script is either a farm script or a construction script.
 * Values arrive as the interpreter holds them (numbers as Double, dicts as Map, lists as List).
 * Those collections are the script's live objects, valid only for the duration of the call; an
 * implementation that keeps a value must copy what it keeps (the script can mutate it afterwards).
 *
 * <p>How a method fails tells the dispatcher whose fault it is: a value the implementation cannot accept is a
 * {@link PlanArgumentException} (the dispatcher reports it as the script's error, with the command and the line;
 * a plan record's own plain {@link IllegalArgumentException} is rethrown typed where the value crosses into the
 * model), a limit on how much one run may keep is a {@link PlanBudgetException}, and anything else - including an
 * untyped {@code IllegalArgumentException} - is a failure of the implementation itself, which propagates unchanged.
 */
public interface PlanApi {
    /**
     * A port is written in a script as {@code "node.port"}: the node id and the port name joined by this character
     * and told apart at the FIRST one, so a port NAME may hold more of them but a node id may not.
     */
    char NODE_PORT_SEPARATOR = '.';

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
