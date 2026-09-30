package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatChannelTest {
    private static final UUID OWNER = new UUID(0, 1);
    private static final UUID OTHER = new UUID(0, 2);

    @Test
    void anOwnerIsACommandViewerUntilAPanelSubmitMarksThem() {
        ChatChannel chat = new ChatChannel();
        assertFalse(chat.isPanel(OWNER), "an unmarked owner gets the command view");
        chat.markPanel(OWNER);
        assertTrue(chat.isPanel(OWNER), "the panel mark hides the command machinery");
        assertFalse(chat.isPanel(OTHER), "the mark is per owner");
    }

    @Test
    void aCommandSubmitReturnsTheOwnerToTheCommandView() {
        ChatChannel chat = new ChatChannel();
        chat.markPanel(OWNER);
        chat.markCommand(OWNER);
        assertFalse(chat.isPanel(OWNER), "the newest submission's path wins");
    }

    @Test
    void theNewestMarkWinsEitherWay() {
        ChatChannel chat = new ChatChannel();
        chat.markCommand(OWNER);
        chat.markPanel(OWNER);
        assertTrue(chat.isPanel(OWNER), "a panel submit after commands hides the lines again");
    }

    @Test
    void logoutForgetsTheMark() {
        ChatChannel chat = new ChatChannel();
        chat.markPanel(OWNER);
        chat.forget(OWNER);
        assertFalse(chat.isPanel(OWNER), "a rejoining owner starts on the command view");
        chat.forget(OWNER);
        assertFalse(chat.isPanel(OWNER), "forgetting twice is harmless");
    }
}
