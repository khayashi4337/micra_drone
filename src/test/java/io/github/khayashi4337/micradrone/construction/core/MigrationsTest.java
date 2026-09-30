package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MigrationsTest {
    @SuppressWarnings("unchecked")
    private static Object renameField(Object payload) {
        Map<String, Object> m = new HashMap<>((Map<String, Object>) payload);
        m.put("total", m.remove("count"));
        return m;
    }

    @Test
    void olderVersionsAreUpgradedAndNewerOrUnknownOnesAreRefused() {
        Migrations mig = new Migrations(Map.of("job", 2)).step("job", 1, MigrationsTest::renameField);
        assertEquals(7L, ((Map<?, ?>) assertDoesNotThrowPayload(mig, new PersistenceEnvelope("job", 1, Map.of("count", 7L))))
                .get("total"));
        assertEquals(Map.of("total", 3L), assertDoesNotThrowPayload(mig, new PersistenceEnvelope("job", 2, Map.of("total", 3L))));
        assertThrows(UnreadableFileException.class, () -> mig.payloadOf(new PersistenceEnvelope("job", 3, Map.of())),
                "a newer save read by older code is refused, not guessed");
        assertThrows(UnreadableFileException.class, () -> mig.payloadOf(new PersistenceEnvelope("ledger", 1, Map.of())));
        Migrations gap = new Migrations(Map.of("job", 3)).step("job", 1, MigrationsTest::renameField);
        assertThrows(UnreadableFileException.class, () -> gap.payloadOf(new PersistenceEnvelope("job", 1, Map.of("count", 1L))),
                "no step from 2 to 3");
    }

    private static Object assertDoesNotThrowPayload(Migrations m, PersistenceEnvelope e) {
        try {
            return m.payloadOf(e);
        } catch (UnreadableFileException ex) {
            throw new AssertionError(ex);
        }
    }
}
