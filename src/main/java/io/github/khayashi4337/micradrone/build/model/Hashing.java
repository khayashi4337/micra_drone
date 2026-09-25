package io.github.khayashi4337.micradrone.build.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 over the UTF-8 bytes of a text, as lowercase hex. */
public final class Hashing {
    private static final String HASH_ALGORITHM = "SHA-256";

    private Hashing() {
    }

    public static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance(HASH_ALGORITHM).digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
