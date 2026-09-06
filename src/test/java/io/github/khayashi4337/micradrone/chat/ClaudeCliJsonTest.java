package io.github.khayashi4337.micradrone.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.khayashi4337.micradrone.chat.ClaudeCliBridge.ClaudeCliResult;

class ClaudeCliJsonTest {

    /** A real {@code claude -p --output-format json} response, captured verbatim during SPK-1. */
    private static final String REAL_SAMPLE = """
            {"duration_api_ms":1777,"stop_reason":"end_turn","session_id":"452fc098-f097-44bc-b812-1997ee1c45dd",\
            "total_cost_usd":0.204042,"usage":{"input_tokens":2},"is_error":false,"num_turns":1,\
            "subtype":"success","api_error_status":null,"result":"OK1","type":"result"}""";

    @Test
    void parsesARealCapturedSuccessResponse() {
        ClaudeCliResult result = ClaudeCliJson.parseResult(REAL_SAMPLE);

        assertTrue(result.success());
        assertEquals("OK1", result.responseText());
        assertEquals("452fc098-f097-44bc-b812-1997ee1c45dd", result.sessionId());
    }

    @Test
    void isErrorTrueIsSurfacedAsAFailureEvenWithAResultField() {
        String json = "{\"is_error\":true,\"result\":\"something went wrong\",\"session_id\":\"s1\"}";
        ClaudeCliResult result = ClaudeCliJson.parseResult(json);

        assertFalse(result.success());
        assertEquals("something went wrong", result.errorMessage());
    }

    @Test
    void garbageInputDoesNotThrowAndIsReportedAsFailure() {
        ClaudeCliResult result = ClaudeCliJson.parseResult("not json at all { [ broken");
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("could not parse"));
    }

    @Test
    void resultContainingEscapedQuotesAndNewlinesRoundTripsCorrectly() {
        String json = "{\"is_error\":false,\"session_id\":\"s1\",\"result\":\"line1\\nline2 \\\"quoted\\\"\"}";
        ClaudeCliResult result = ClaudeCliJson.parseResult(json);

        assertEquals("line1\nline2 \"quoted\"", result.responseText());
    }
}
