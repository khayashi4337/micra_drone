package io.github.khayashi4337.micradrone.build.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HashingTest {
    @Test
    void knownSha256Vectors() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hashing.sha256Hex("abc"));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Hashing.sha256Hex(""));
    }

    @Test
    void utf8IsUsedForNonAscii() {
        assertEquals("da7e0aaaa86bf0d03a1a72ccd90592f44b71129febcd7a38b081afebabe52819", Hashing.sha256Hex("屋根"));
    }
}
