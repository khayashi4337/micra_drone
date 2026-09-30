package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JobViewsTest {
    @Test
    void aStatusIsPlainJsonWithEveryField() {
        JobStatus st = new JobStatus("job-1", new UUID(0, 1), JobKind.BUILD, JobState.PAUSED, PauseReason.MATERIALS_MISSING, 3, 25, 0,
                1, 2, "", "claim-job-1", "minecraft:overworld");
        Map<String, Object> t = JobViews.statusTree(st);
        assertEquals("PAUSED", t.get("state"));
        assertEquals("MATERIALS_MISSING", t.get("pause"));
        assertEquals(3, ((Number) t.get("cursor")).intValue());
        assertEquals(25, ((Number) t.get("total")).intValue());
        String json = MiniJson.write(t);
        assertEquals(t.get("jobId"), ((Map<?, ?>) MiniJson.parse(json)).get("jobId"), "round-trips through the JSON writer");
    }

    @Test
    void aRunningJobHasNoPauseAndIssuesKeepTheirIds() {
        JobStatus st = new JobStatus("job-1", new UUID(0, 1), JobKind.BUILD, JobState.RUNNING, null, 3, 25, 0, 0, 0, "", "c",
                "minecraft:overworld");
        assertNull(JobViews.statusTree(st).get("pause"));
        Issue i = Issue.of(IssueCode.E_SITE_BLOCKED, "unloaded", List.of("manifest"), "x");
        Map<String, Object> it = JobViews.issueTree(i);
        assertEquals("E-SITE-BLOCKED:manifest#unloaded", it.get("id"));
        assertEquals("E-SITE-BLOCKED", it.get("code"));
        assertEquals(false, it.get("acceptable"));
        assertEquals(ChildMessages.issue(IssueCode.E_SITE_BLOCKED), it.get("childKey"));
    }
}
