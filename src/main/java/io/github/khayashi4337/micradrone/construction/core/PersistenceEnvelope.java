package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.CanonicalJson;
import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** A saved value with its type and schema version (design 01, section 0): canonical JSON, gzip, then sealed. */
public record PersistenceEnvelope(String type, int schemaVersion, Object payload) {
    static final String KEY_TYPE = "type";
    static final String KEY_VERSION = "schemaVersion";
    static final String KEY_PAYLOAD = "payload";

    public PersistenceEnvelope {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
    }

    public byte[] toBytes() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put(KEY_TYPE, type);
        tree.put(KEY_VERSION, (long) schemaVersion);
        tree.put(KEY_PAYLOAD, payload);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(CanonicalJson.write(tree).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("in-memory gzip cannot fail", e);
        }
        return SealedFile.seal(out.toByteArray());
    }

    public static PersistenceEnvelope fromBytes(byte[] bytes) throws UnreadableFileException {
        byte[] body = SealedFile.unseal(bytes).orElseThrow(() -> new UnreadableFileException("check value mismatch"));
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(body))) {
            Map<String, Object> tree = JsonReads.map(MiniJson.parse(new String(gz.readAllBytes(), StandardCharsets.UTF_8)), "envelope");
            return new PersistenceEnvelope(JsonReads.string(tree.get(KEY_TYPE), KEY_TYPE),
                    JsonReads.integer(tree.get(KEY_VERSION), KEY_VERSION), tree.get(KEY_PAYLOAD));
        } catch (IOException | IllegalArgumentException e) {
            throw new UnreadableFileException("unreadable envelope: " + e.getMessage());
        }
    }
}
