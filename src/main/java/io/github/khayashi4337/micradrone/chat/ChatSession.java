package io.github.khayashi4337.micradrone.chat;

import java.util.ArrayList;
import java.util.List;

/**
 * One controller's chat transcript plus the claude CLI session id it resumes (SPK-1: a real UUID
 * we hand to --session-id/--resume, not a boolean "continue" flag - see
 * docs/investigations/spk1_claude_cli_behavior.md).
 */
public final class ChatSession {
    private final ControllerKey key;
    private String cliSessionId;
    private final List<ChatMessage> messages;

    public ChatSession(ControllerKey key, String cliSessionId, List<ChatMessage> messages) {
        this.key = key;
        this.cliSessionId = cliSessionId;
        this.messages = new ArrayList<>(messages);
    }

    /** A brand new, empty session for {@code key} - no CLI session started yet. */
    public static ChatSession empty(ControllerKey key) {
        return new ChatSession(key, null, List.of());
    }

    public ControllerKey key() {
        return key;
    }

    /** Null until the first successful claude -p call assigns one. */
    public String cliSessionId() {
        return cliSessionId;
    }

    public void setCliSessionId(String cliSessionId) {
        this.cliSessionId = cliSessionId;
    }

    public List<ChatMessage> messages() {
        return List.copyOf(messages);
    }

    public void addMessage(ChatMessage message) {
        messages.add(message);
    }

    /** Replaces the transcript wholesale - used by the compact flow (T-4b) to swap in a summary. */
    public void replaceMessages(List<ChatMessage> replacement) {
        messages.clear();
        messages.addAll(replacement);
    }
}
