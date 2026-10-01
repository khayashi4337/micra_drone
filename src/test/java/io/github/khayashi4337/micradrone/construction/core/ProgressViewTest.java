package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProgressViewTest {
    private static JobStatus status(JobState state, PauseReason pause, int cursor, int total) {
        return new JobStatus("job-1", new UUID(0, 1), JobKind.BUILD, state, pause, cursor, total, 0, 1, 0, 2, "",
                "claim-job-1", "minecraft:overworld");
    }

    @Test
    void percentIsTheIntegerShareOfPlacedBlocks() {
        Map<String, Object> t = ProgressView.tree(status(JobState.RUNNING, null, 3, 25));
        assertEquals("job-1", t.get("jobId"));
        assertEquals("RUNNING", t.get("state"));
        assertEquals(3, ((Number) t.get("cursor")).intValue());
        assertEquals(25, ((Number) t.get("total")).intValue());
        assertEquals(12, ((Number) t.get("percent")).intValue(), "3 of 25 placed is 12%");
        assertNull(t.get("pause"));
        assertEquals(2, ((Number) t.get("unrepaired")).intValue());
        assertEquals(1, ((Number) t.get("conflicts")).intValue());
        assertEquals(false, t.get("done"));
        assertEquals(false, t.get("partial"));
    }

    @Test
    void anEmptyJobShowsZeroPercentInsteadOfDividingByZero() {
        Map<String, Object> t = ProgressView.tree(status(JobState.RUNNING, null, 0, 0));
        assertEquals(0, ((Number) t.get("percent")).intValue());
    }

    @Test
    void aVerifiedJobIsDoneAndAPartialJobIsPartial() {
        assertEquals(true, ProgressView.tree(status(JobState.VERIFIED, null, 25, 25)).get("done"));
        Map<String, Object> partial = ProgressView.tree(status(JobState.PARTIAL, null, 20, 25));
        assertEquals(true, partial.get("partial"));
        assertEquals(false, partial.get("done"));
    }

    @Test
    void aPausedJobCarriesItsShownPause() {
        Map<String, Object> t = ProgressView.tree(status(JobState.PAUSED, PauseReason.SITE_CHANGED, 3, 25));
        assertEquals("SITE_CHANGED", t.get("pause"));
    }

    @Test
    void theProgressTableRoundTripsThroughTheJsonWriter() {
        String json = MiniJson.write(ProgressView.tree(status(JobState.RUNNING, null, 3, 25)));
        Map<?, ?> parsed = (Map<?, ?>) MiniJson.parse(json);
        assertEquals("job-1", parsed.get("jobId"));
        assertEquals(12, ((Number) parsed.get("percent")).intValue());
    }
}
