package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;

/** Generates the cells of one building part. Deterministic: the same node and parameters give the same cells. */
public interface PartGenerator {
    void generate(GenContext ctx, PlanNode node, Params p);

    /** Runs after every part has been generated (for checks that need the whole canvas). */
    default void afterAll(GenContext ctx, PlanNode node, Params p) {
    }
}
