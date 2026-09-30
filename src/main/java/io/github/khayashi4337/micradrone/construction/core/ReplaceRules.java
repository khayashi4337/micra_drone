package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.BlockMatch;
import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.ReplacePolicy;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.Set;

/**
 * The one set of replacement rules (F-5): the safety envelope uses it on the pinned survey before approval and the
 * executor on the live world just before each placement, so the two can never disagree.
 */
public final class ReplaceRules {
    private ReplaceRules() {
    }

    public static ReplaceDecision decide(Placement p, WorldCell cell, boolean journaled, boolean placedByProject) {
        if (!cell.loaded()) {
            throw new IllegalArgumentException("decide only on loaded positions; an unloaded one pauses the job");
        }
        BlockSpec now = cell.block();
        if (journaled && BlockMatch.satisfies(now, p.block(), Set.of())) {
            return new ReplaceDecision.AlreadyDone();
        }
        if (placedByProject) {
            return new ReplaceDecision.Place(Destruction.NONE);
        }
        if (cell.traits().contains(CellTrait.UNBREAKABLE)) {
            return new ReplaceDecision.Refused(Refusal.UNBREAKABLE);
        }
        if (cell.observed().hasBlockEntity()) {
            boolean mayTakeEmptyContainer = !(p.replaces() instanceof ReplacePolicy.AirOnly)
                    && !(p.replaces() instanceof ReplacePolicy.Expect);
            return mayTakeEmptyContainer && cell.traits().contains(CellTrait.EMPTY_CONTAINER)
                    ? new ReplaceDecision.Place(Destruction.EMPTY_CONTAINER)
                    : new ReplaceDecision.Refused(Refusal.FOREIGN_BLOCK_ENTITY);
        }
        return switch (p.replaces()) {
            case ReplacePolicy.AirOnly a -> now.isAir() ? new ReplaceDecision.Place(Destruction.NONE)
                    : new ReplaceDecision.Refused(Refusal.NOT_REPLACEABLE);
            case ReplacePolicy.Expect e -> now.blockId().equals(e.blockId()) ? new ReplaceDecision.Place(Destruction.NONE)
                    : new ReplaceDecision.Refused(Refusal.EXPECTED_OTHER);
            case ReplacePolicy.Replaceable r -> natural(cell, now, false);
        };
    }

    static ReplaceDecision natural(WorldCell cell, BlockSpec now, boolean terraform) {
        if (now.isAir()) {
            return new ReplaceDecision.Place(Destruction.NONE);
        }
        if (cell.traits().contains(CellTrait.FLUID)) {
            return new ReplaceDecision.Place(Destruction.FLUID);
        }
        if (cell.traits().contains(CellTrait.LEAVES)) {
            return new ReplaceDecision.Place(Destruction.LEAVES);
        }
        if (cell.traits().contains(CellTrait.REPLACEABLE)) {
            return new ReplaceDecision.Place(Destruction.NONE);
        }
        if (terraform && cell.traits().contains(CellTrait.TERRAFORMABLE)) {
            return new ReplaceDecision.Place(Destruction.TERRAIN);
        }
        return new ReplaceDecision.Refused(terraform ? Refusal.NOT_TERRAFORMABLE : Refusal.NOT_REPLACEABLE);
    }
}
