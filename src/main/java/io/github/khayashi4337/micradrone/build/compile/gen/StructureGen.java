package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.Params;

/** A building has no blocks of its own; it only gives its children a frame. */
final class StructureGen implements PartGenerator {
    @Override
    public void generate(GenContext ctx, PlanNode node, Params p) {
        // Validate that it can be placed: its children read their frame from its origin.
        if (ctx.info(node.id()).origin() == null) {
            throw ctx.fail(node, IssueCode.E_ANCHOR, GenContext.KEY_ANCHOR, "建屋の位置を決められません");
        }
    }
}
