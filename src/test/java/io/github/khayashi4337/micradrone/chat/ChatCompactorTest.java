package io.github.khayashi4337.micradrone.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ChatCompactorTest {

    private static final ControllerKey KEY = new ControllerKey("minecraft:overworld", 0, 64, 0);

    @Test
    void applyingASummaryClearsTheCliSessionIdSoTheNextSendStartsFresh() {
        ChatSession session = ChatSession.empty(KEY);
        session.setCliSessionId("old-session-id");
        session.addMessage(new ChatMessage("user", "question 1", 1L));
        session.addMessage(new ChatMessage("assistant", "answer 1", 2L));

        ChatCompactor.applySummary(session, "要約テキスト");

        assertNull(session.cliSessionId());
    }

    @Test
    void applyingASummaryReplacesTheTranscriptWithJustTheSummary() {
        ChatSession session = ChatSession.empty(KEY);
        session.addMessage(new ChatMessage("user", "question 1", 1L));
        session.addMessage(new ChatMessage("assistant", "answer 1", 2L));

        ChatCompactor.applySummary(session, "要約テキスト");

        assertEquals(1, session.messages().size());
        assertEquals("summary", session.messages().get(0).role());
        assertEquals("要約テキスト", session.messages().get(0).text());
    }
}
