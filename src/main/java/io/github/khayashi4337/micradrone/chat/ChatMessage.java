package io.github.khayashi4337.micradrone.chat;

/**
 * One turn in a chat transcript. {@code role} is one of the {@code ROLE_*} constants below - the
 * single place those strings are spelled out, since they're persisted by ChatHistoryStore and
 * matched again by IdeScreen and ChatCompactor.
 */
public record ChatMessage(String role, String text, long timestamp) {
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    /** The one message left after a compact: the AI's own summary of everything before it. */
    public static final String ROLE_SUMMARY = "summary";
}
