package io.github.khayashi4337.micradrone.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatHistoryStoreTest {

    @TempDir
    Path tempDir;

    private static final ControllerKey OVERWORLD_KEY = new ControllerKey("minecraft:overworld", 1, 64, -3);

    @Test
    void loadingWithNoFileYetReturnsAFreshEmptySession() {
        ChatSession session = ChatHistoryStore.load(tempDir, OVERWORLD_KEY);
        assertEquals(List.of(), session.messages());
        assertNull(session.cliSessionId());
    }

    @Test
    void savingThenLoadingRoundTripsTheSessionExactly() throws IOException {
        ChatSession session = ChatSession.empty(OVERWORLD_KEY);
        session.setCliSessionId("abc-123");
        session.addMessage(new ChatMessage("user", "move north please", 1000L));
        session.addMessage(new ChatMessage("assistant", "line one\nline two\twith a tab", 2000L));

        ChatHistoryStore.save(tempDir, session);
        ChatSession loaded = ChatHistoryStore.load(tempDir, OVERWORLD_KEY);

        assertEquals("abc-123", loaded.cliSessionId());
        assertEquals(session.messages(), loaded.messages());
    }

    @Test
    void differentDimensionsAtTheSameCoordinatesDoNotCollide() throws IOException {
        ControllerKey netherKey = new ControllerKey("minecraft:the_nether", 1, 64, -3);
        ChatSession overworldSession = ChatSession.empty(OVERWORLD_KEY);
        overworldSession.addMessage(new ChatMessage("user", "overworld message", 1L));
        ChatSession netherSession = ChatSession.empty(netherKey);
        netherSession.addMessage(new ChatMessage("user", "nether message", 1L));

        ChatHistoryStore.save(tempDir, overworldSession);
        ChatHistoryStore.save(tempDir, netherSession);

        assertEquals("overworld message", ChatHistoryStore.load(tempDir, OVERWORLD_KEY).messages().get(0).text());
        assertEquals("nether message", ChatHistoryStore.load(tempDir, netherKey).messages().get(0).text());
    }

    @Test
    void aCorruptFileFallsBackToAFreshEmptySessionInsteadOfThrowing() throws IOException {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve(OVERWORLD_KEY.storageFileName()), "\nnot-enough-fields-here\n");

        ChatSession session = ChatHistoryStore.load(tempDir, OVERWORLD_KEY);

        assertEquals(List.of(), session.messages());
    }

    @Test
    void parseRejectsAMalformedLineDirectly() {
        assertThrows(IllegalArgumentException.class,
                () -> ChatHistoryStore.parse(OVERWORLD_KEY, "\nonly-one-field\n"));
    }

    @Test
    void worldDirectoryNameKeepsSafeCharactersAndReplacesEverythingElse() {
        assertEquals("kuni6", ChatHistoryStore.worldDirectoryName("kuni6"));
        assertEquals("New_World-2", ChatHistoryStore.worldDirectoryName("New World-2"));
        assertEquals("play.example.com_25565", ChatHistoryStore.worldDirectoryName("play.example.com:25565"));
        assertEquals("a_b_c", ChatHistoryStore.worldDirectoryName("a/b\\c"));
    }

    @Test
    void worldDirectoryNameNeverYieldsAnEmptyPathSegment() {
        assertEquals("unknown", ChatHistoryStore.worldDirectoryName(""));
        assertEquals("unknown", ChatHistoryStore.worldDirectoryName("   "));
        assertEquals("unknown", ChatHistoryStore.worldDirectoryName(null));
    }

    @Test
    void serializeThenParseRoundTripsSpecialCharactersInMessageText() {
        ChatSession session = ChatSession.empty(OVERWORLD_KEY);
        session.addMessage(new ChatMessage("assistant", "back\\slash, tab\t, newline\n end", 42L));

        String serialized = ChatHistoryStore.serialize(session);
        ChatSession parsed = ChatHistoryStore.parse(OVERWORLD_KEY, serialized);

        assertEquals("back\\slash, tab\t, newline\n end", parsed.messages().get(0).text());
    }
}
