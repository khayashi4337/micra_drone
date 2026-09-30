package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Site;
import java.util.Objects;

/**
 * Moves a submitted plan to where the player stands for {@code submit-here} (Task 17): only the site's dimension,
 * origin and facing change; the design, bounds, style and provenance are kept. The pinned terrain digest is cleared -
 * it described the old ground, and the new site gets a fresh survey during submit (F-3).
 */
public final class SiteRelocation {
    private SiteRelocation() {
    }

    /** The same plan at a new site. A site-less plan cannot be moved: it has no bounds to place. */
    public static SemanticPlan relocate(SemanticPlan plan, String dimension, IntPos origin, Facing facing) {
        Site old = Objects.requireNonNull(plan, "plan").site();
        if (old == null) {
            throw new IllegalArgumentException("a plan without a site cannot be relocated");
        }
        Site site = new Site(Objects.requireNonNull(dimension, "dimension"),
                new BuildFrame(Objects.requireNonNull(origin, "origin"), Objects.requireNonNull(facing, "facing")),
                old.localBounds(), "", old.claimId());
        return new SemanticPlan(plan.schemaVersion(), plan.planId(), plan.revision(), plan.parentRevision(), site,
                plan.style(), plan.nodes(), plan.connections(), plan.logistics(), plan.provenance());
    }
}
