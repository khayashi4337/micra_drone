package io.github.khayashi4337.micradrone.chat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads/writes a controller's chat transcript, one plain-text file per {@link ControllerKey}
 * inside a per-world directory under the client's game folder ({@code micradrone/chat/<world>/},
 * see {@link #worldDirectoryName}) - same {@code Path}/{@code Files} convention as
 * {@code ScriptFileStore}, no JSON dependency. Client-local only: never synced to the server, per
 * the confirmed requirement, which is also why it lives under the game folder rather than any
 * world save.
 */
public final class ChatHistoryStore {
    private static final String FIELD_SEPARATOR = "\t";
    private static final String UNKNOWN_WORLD_DIRECTORY = "unknown";

    private ChatHistoryStore() {
    }

    /**
     * A filesystem-safe directory name for one world, derived from something unique to it (a
     * save folder name, a server address). {@link ControllerKey} only covers dimension + position,
     * so without this a controller at (10,64,10) in one save would share its transcript with one
     * at the same spot in another save or on a server. Every character outside
     * {@code [A-Za-z0-9._-]} becomes {@code _}; a blank id falls back to a fixed name rather than
     * an empty path segment.
     */
    public static String worldDirectoryName(String rawWorldId) {
        if (rawWorldId == null || rawWorldId.isBlank()) {
            return UNKNOWN_WORLD_DIRECTORY;
        }
        String sanitized = rawWorldId.replaceAll("[^A-Za-z0-9._-]", "_");
        // "." and ".." are path-traversal segments that resolve() would treat as parent/current
        // directory - reject them so a server address of ".." can't escape micradrone/chat/.
        if (sanitized.equals(".") || sanitized.equals("..")) {
            return UNKNOWN_WORLD_DIRECTORY;
        }
        return sanitized;
    }

    /** The session on disk for {@code key}, or a fresh empty one if there's nothing there yet. */
    public static ChatSession load(Path historyDir, ControllerKey key) {
        Path file = historyDir.resolve(key.storageFileName());
        if (!Files.isRegularFile(file)) {
            return ChatSession.empty(key);
        }
        try {
            return parse(key, Files.readString(file));
        } catch (IOException | RuntimeException corruptOrUnreadable) {
            // A damaged or half-written file must not block the player from chatting again -
            // fall back to a fresh session rather than propagate the failure (Codex review finding).
            return ChatSession.empty(key);
        }
    }

    /** Writes {@code session} atomically (write to a temp file, then rename over the real one). */
    public static void save(Path historyDir, ChatSession session) throws IOException {
        Files.createDirectories(historyDir);
        Path file = historyDir.resolve(session.key().storageFileName());
        Path tmp = historyDir.resolve(session.key().storageFileName() + ".tmp");
        Files.writeString(tmp, serialize(session));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** First line: the CLI session id (blank if none yet). One line per message after that. */
    static String serialize(ChatSession session) {
        StringBuilder sb = new StringBuilder();
        sb.append(session.cliSessionId() == null ? "" : escape(session.cliSessionId())).append('\n');
        for (ChatMessage message : session.messages()) {
            sb.append(escape(message.role())).append(FIELD_SEPARATOR)
                    .append(message.timestamp()).append(FIELD_SEPARATOR)
                    .append(escape(message.text())).append('\n');
        }
        return sb.toString();
    }

    static ChatSession parse(ControllerKey key, String content) {
        String[] lines = content.split("\n", -1);
        String cliSessionId = unescape(lines[0]);
        if (cliSessionId.isEmpty()) {
            cliSessionId = null;
        }
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                continue;
            }
            String[] fields = lines[i].split(FIELD_SEPARATOR, 3);
            if (fields.length != 3) {
                throw new IllegalArgumentException("malformed chat history line: " + lines[i]);
            }
            messages.add(new ChatMessage(unescape(fields[0]), unescape(fields[2]), Long.parseLong(fields[1])));
        }
        return new ChatSession(key, cliSessionId, messages);
    }

    /** Backslash-escapes newline/tab/backslash so a message's own text can never be mistaken for a field boundary. */
    private static String escape(String raw) {
        return raw.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t");
    }

    private static String unescape(String escaped) {
        StringBuilder sb = new StringBuilder(escaped.length());
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (c == '\\' && i + 1 < escaped.length()) {
                char next = escaped.charAt(++i);
                sb.append(switch (next) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    default -> next;
                });
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
