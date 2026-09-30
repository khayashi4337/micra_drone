package io.github.khayashi4337.micradrone.build.model;

import java.util.Map;

/** One edit of a plan. A script is the program form of a list of these (one command = one operation). */
public sealed interface PlanOp {
    record AddNode(PlanNode node) implements PlanOp {
    }

    record UpdateParams(String id, Map<String, ParamValue> params) implements PlanOp {
        public UpdateParams {
            params = SortedCopies.map(params);
        }
    }

    record MoveNode(String id, Anchor anchor) implements PlanOp {
    }

    record RemoveNode(String id) implements PlanOp {
    }

    record AddConnection(Connection connection) implements PlanOp {
    }

    record RemoveConnection(String id) implements PlanOp {
    }

    record SetStyle(StyleSpec style) implements PlanOp {
    }

    record SetSite(Site site) implements PlanOp {
    }

    /** {@code logistics} may be null to clear the logistics of the plan. */
    record SetLogistics(LogisticsPlan logistics) implements PlanOp {
    }
}
