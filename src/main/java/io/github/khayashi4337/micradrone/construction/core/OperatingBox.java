package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.LogisticsPlan;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;

/**
 * The world box a claim protects (04 F-4): the building's box plus every dock's pad and the air above it that an
 * airship needs (D-24's P4 part; parts' EffectSpec reach joins in P10 and P13). Rotating by quarter turns maps a box's
 * opposite corners to opposite corners, so two corners are enough.
 */
public final class OperatingBox {
    private OperatingBox() {
    }

    public static Box of(PlacementManifest m, SemanticPlan plan) {
        Box out = m.worldBounds();
        if (plan.logistics() == null || plan.site() == null) {
            return out;
        }
        BuildFrame frame = plan.site().frame();
        for (LogisticsPlan.Dock d : plan.logistics().docks()) {
            out = ClaimBook.union(out, toWorld(frame, d.padBox()));
            out = ClaimBook.union(out, toWorld(frame, d.clearanceBox()));
        }
        return out;
    }

    /** The two-corner mapping from a local box to its world box (shared with the submit path's site box). */
    public static Box toWorld(BuildFrame frame, Box local) {
        IntPos a = frame.toWorld(new LocalPos(local.minA(), local.minB(), local.minC()));
        IntPos b = frame.toWorld(new LocalPos(local.maxA(), local.maxB(), local.maxC()));
        return Box.of(a.x(), a.y(), a.z(), b.x(), b.y(), b.z());
    }
}
