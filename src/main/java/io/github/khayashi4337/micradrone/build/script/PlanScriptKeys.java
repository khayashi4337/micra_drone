package io.github.khayashi4337.micradrone.build.script;

import java.util.List;

/**
 * The dict keys of the {@code logistics(...)} and {@code connect(...)} arguments: ONE spelling for the
 * {@link PlanRecorder}, which reads them, and the {@link PlanScriptWriter}, which writes them, so the two cannot
 * drift apart. The lists hold the keys each dict may carry, in the order the recorder names them in its refusals.
 */
final class PlanScriptKeys {
    static final String DOCK_ID = "id";
    static final String DOCK_PAD = "pad";
    static final String DOCK_CLEARANCE = "clearance";
    static final String DOCK_APPROACH = "approach";
    static final String DOCK_PORTS = "ports";
    static final String DOCK_CONNECTORS = "connectors";
    static final List<String> DOCK_KEYS = List.of(DOCK_ID, DOCK_PAD, DOCK_CLEARANCE, DOCK_APPROACH, DOCK_PORTS, DOCK_CONNECTORS);

    static final String ROUTE_ID = "id";
    static final String ROUTE_FROM = "from";
    static final String ROUTE_TO = "to";
    static final String ROUTE_WAYPOINTS = "waypoints";
    static final String ROUTE_AIRSHIP = "airship";
    static final List<String> ROUTE_KEYS = List.of(ROUTE_ID, ROUTE_FROM, ROUTE_TO, ROUTE_WAYPOINTS, ROUTE_AIRSHIP);

    static final String FLOW_ITEM = "item";
    static final String FLOW_PER_MIN = "per_min";
    static final String FLOW_FROM = "from";
    static final String FLOW_TO = "to";
    static final List<String> FLOW_KEYS = List.of(FLOW_ITEM, FLOW_PER_MIN, FLOW_FROM, FLOW_TO);

    static final String CONSTRAINT_MAX_LENGTH = "max_length";
    static final String CONSTRAINT_AVOID = "avoid";
    static final String CONSTRAINT_MAX_TURNS = "max_turns";
    static final String CONSTRAINT_ENTRY_DIRS = "entry_dirs";
    static final List<String> CONSTRAINT_KEYS = List.of(CONSTRAINT_MAX_LENGTH, CONSTRAINT_AVOID, CONSTRAINT_MAX_TURNS, CONSTRAINT_ENTRY_DIRS);

    private PlanScriptKeys() {
    }
}
