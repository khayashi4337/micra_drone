package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.SiteSurvey;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** The surveys the server issued and pinned (04 F-3): kept ten minutes, so approval recompiles on the very same survey. */
public final class SurveyCache {
    /** F-3's default of ten minutes, at 20 ticks per second. */
    public static final long SURVEY_TTL_TICKS = 20L * 60L * 10L;

    private record Pinned(SiteSurvey survey, long expiresTick) {
    }

    private final Map<String, Pinned> byDigest = new HashMap<>();

    public void pin(SiteSurvey survey, long now) {
        byDigest.put(survey.digest(), new Pinned(survey, now + SURVEY_TTL_TICKS));
    }

    public Optional<SiteSurvey> find(String digest, long now) {
        Pinned p = byDigest.get(digest);
        return p == null || now > p.expiresTick() ? Optional.empty() : Optional.of(p.survey());
    }

    public void expire(long now) {
        byDigest.values().removeIf(p -> now > p.expiresTick());
    }

    public int size() {
        return byDigest.size();
    }
}
