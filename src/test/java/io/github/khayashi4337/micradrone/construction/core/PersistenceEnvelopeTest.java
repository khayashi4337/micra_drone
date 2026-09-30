package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PersistenceEnvelopeTest {
    @Test
    void anEnvelopeRoundTripsAndRefusesDamage() throws Exception {
        PersistenceEnvelope e = new PersistenceEnvelope("job", 1, Map.of("jobId", "job-1", "cursor", 5L));
        byte[] bytes = e.toBytes();
        PersistenceEnvelope back = PersistenceEnvelope.fromBytes(bytes);
        assertEquals("job", back.type());
        assertEquals(1, back.schemaVersion());
        assertEquals("job-1", ((Map<?, ?>) back.payload()).get("jobId"));
        bytes[bytes.length / 2] ^= 1;
        assertThrows(UnreadableFileException.class, () -> PersistenceEnvelope.fromBytes(bytes));
    }
}
