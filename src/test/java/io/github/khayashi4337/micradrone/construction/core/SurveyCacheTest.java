package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.SiteSurvey;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.Box;
import org.junit.jupiter.api.Test;

class SurveyCacheTest {
    @Test
    void aPinnedSurveyLivesTenMinutes() {
        SurveyCache cache = new SurveyCache();
        SiteSurvey s = SiteSurvey.air(TestManifests.DIM, new Box(0, 0, 0, 3, 3, 3));
        cache.pin(s, 100L);
        assertEquals(s.digest(), cache.find(s.digest(), 100L + SurveyCache.SURVEY_TTL_TICKS).orElseThrow().digest());
        assertTrue(cache.find(s.digest(), 101L + SurveyCache.SURVEY_TTL_TICKS).isEmpty());
        cache.expire(101L + SurveyCache.SURVEY_TTL_TICKS);
        assertEquals(0, cache.size());
        assertEquals(12_000L, SurveyCache.SURVEY_TTL_TICKS);
    }
}
