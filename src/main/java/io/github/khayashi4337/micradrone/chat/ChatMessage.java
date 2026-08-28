package io.github.khayashi4337.micradrone.chat;

/** One turn in a chat transcript. {@code role} is "user" or "assistant". */
public record ChatMessage(String role, String text, long timestamp) {
}
