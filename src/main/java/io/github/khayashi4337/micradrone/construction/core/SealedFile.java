package io.github.khayashi4337.micradrone.construction.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Optional;

/**
 * A file body with a magic header and its SHA-256 at the end (04 F-2: "the hash or the trailing check value"), so a
 * torn, truncated or flipped file is detected on load instead of being trusted.
 */
public final class SealedFile {
    public static final byte[] MAGIC = "MDSEAL1\n".getBytes(StandardCharsets.US_ASCII);
    public static final int DIGEST_BYTES = 32;
    private static final String HASH_ALGORITHM = "SHA-256";

    private SealedFile() {
    }

    public static byte[] seal(byte[] body) {
        byte[] out = new byte[MAGIC.length + body.length + DIGEST_BYTES];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        System.arraycopy(body, 0, out, MAGIC.length, body.length);
        System.arraycopy(sha256(body), 0, out, MAGIC.length + body.length, DIGEST_BYTES);
        return out;
    }

    public static Optional<byte[]> unseal(byte[] file) {
        if (file.length < MAGIC.length + DIGEST_BYTES || !Arrays.equals(file, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
            return Optional.empty();
        }
        byte[] body = Arrays.copyOfRange(file, MAGIC.length, file.length - DIGEST_BYTES);
        byte[] digest = Arrays.copyOfRange(file, file.length - DIGEST_BYTES, file.length);
        return MessageDigest.isEqual(digest, sha256(body)) ? Optional.of(body) : Optional.empty();
    }

    private static byte[] sha256(byte[] body) {
        try {
            return MessageDigest.getInstance(HASH_ALGORITHM).digest(body);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
