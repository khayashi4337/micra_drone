package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The size part of the safety envelope (04 F-5), checked on the site's world box BEFORE any block or chunk
 * is read: a plan can name a localBounds reaching the coordinate limit, and surveying or load-checking it
 * would stall the main thread. The side lengths are computed in long so the full int range cannot wrap.
 * The issue is the same code and the same id {@link SafetyEnvelope} emits for the same reason.
 */
public final class SiteBoxLimits {
    private SiteBoxLimits() {
    }

    public static Optional<Issue> check(Box worldBox, SafetyLimits limits) {
        long sx = (long) worldBox.maxA() - worldBox.minA() + 1;
        long sy = (long) worldBox.maxB() - worldBox.minB() + 1;
        long sz = (long) worldBox.maxC() - worldBox.minC() + 1;
        if (sx <= limits.maxSizeX() && sy <= limits.maxSizeY() && sz <= limits.maxSizeZ()) {
            return Optional.empty();
        }
        String size = sx + "x" + sy + "x" + sz;
        String max = limits.maxSizeX() + "x" + limits.maxSizeY() + "x" + limits.maxSizeZ();
        return Optional.of(Issue.of(IssueCode.E_OUT_OF_BOUNDS, SafetyEnvelope.KEY_SIZE,
                List.of(SafetyEnvelope.SUBJECT_MANIFEST),
                "施工の範囲が大きすぎます(" + size + "。上限は" + max + ")",
                Map.of(SafetyEnvelope.DATA_COUNT, size, SafetyEnvelope.DATA_LIMIT, max), List.of()));
    }
}
