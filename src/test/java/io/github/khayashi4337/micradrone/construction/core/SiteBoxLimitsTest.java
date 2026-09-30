package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SiteBoxLimitsTest {
    private static final SafetyLimits LIMITS = SafetyLimits.defaults(-64, 320);

    @Test
    void aBoxAtTheLimitsPasses() {
        // exactly the default sizes: 128 x 96 x 128
        assertTrue(SiteBoxLimits.check(new Box(0, 0, 0, 127, 95, 127), LIMITS).isEmpty());
    }

    @Test
    void eachAxisIsCheckedAgainstItsOwnLimit() {
        assertTrue(SiteBoxLimits.check(new Box(0, 0, 0, 128, 0, 0), LIMITS).isPresent(), "x one over");
        assertTrue(SiteBoxLimits.check(new Box(0, 0, 0, 0, 96, 0), LIMITS).isPresent(), "y one over");
        assertTrue(SiteBoxLimits.check(new Box(0, 0, 0, 0, 0, 128), LIMITS).isPresent(), "z one over");
    }

    @Test
    void anOversizedBoxFailsWithTheSameIssueTheEnvelopeEmits() {
        Optional<Issue> issue = SiteBoxLimits.check(new Box(0, 0, 0, 128, 0, 0), LIMITS);
        assertTrue(issue.isPresent());
        assertEquals(IssueCode.E_OUT_OF_BOUNDS, issue.get().code());
        assertEquals("E-OUT-OF-BOUNDS:manifest#size", issue.get().id(),
                "the same code and id SafetyEnvelope gives for the same reason");
    }

    @Test
    void theFarEdgeOfTheWorldCountsInLongWithoutOverflow() {
        // a plan may name localBounds reaching the coordinate limit: a single column of full height
        Box box = new Box(0, Integer.MIN_VALUE, 0, 0, Integer.MAX_VALUE, 0);
        assertTrue(SiteBoxLimits.check(box, LIMITS).isPresent(),
                "a y side of 2^32 blocks must not wrap around to a small number");
        Box wide = new Box(Integer.MIN_VALUE, 0, 0, Integer.MAX_VALUE, 0, 0);
        assertTrue(SiteBoxLimits.check(wide, LIMITS).isPresent(), "the same for x");
    }

    @Test
    void farAwayButSmallIsFine() {
        assertTrue(SiteBoxLimits.check(new Box(-30_000_000, 0, -30_000_000, -29_999_999, 5, -29_999_999), LIMITS)
                .isEmpty(), "position is not the limit; only the side lengths are");
    }
}
