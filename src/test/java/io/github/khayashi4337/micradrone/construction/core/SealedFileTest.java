package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SealedFileTest {
    private static final byte[] BODY = "hello journal".getBytes(StandardCharsets.UTF_8);

    @Test
    void aSealedBodyComesBackUnchanged() {
        assertArrayEquals(BODY, SealedFile.unseal(SealedFile.seal(BODY)).orElseThrow());
    }

    @Test
    void anyDamageIsDetected() {
        byte[] sealed = SealedFile.seal(BODY);
        byte[] flipped = sealed.clone();
        flipped[SealedFile.MAGIC.length + 2] ^= 1;
        assertTrue(SealedFile.unseal(flipped).isEmpty(), "one flipped bit");
        assertTrue(SealedFile.unseal(Arrays.copyOf(sealed, sealed.length - 1)).isEmpty(), "a torn tail");
        byte[] wrongMagic = sealed.clone();
        wrongMagic[0] = 'X';
        assertTrue(SealedFile.unseal(wrongMagic).isEmpty());
        assertTrue(SealedFile.unseal(new byte[3]).isEmpty(), "too short to hold anything");
    }
}
