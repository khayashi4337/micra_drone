package io.github.khayashi4337.micradrone.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MiniJsonTest {

    @Test
    @SuppressWarnings("unchecked")
    void parsesAFlatObjectWithMixedTypes() {
        Map<String, Object> obj = (Map<String, Object>) MiniJson.parse(
                "{\"a\":\"text\",\"b\":42,\"c\":true,\"d\":false,\"e\":null}");

        assertEquals("text", obj.get("a"));
        assertEquals(42.0, obj.get("b"));
        assertEquals(Boolean.TRUE, obj.get("c"));
        assertEquals(Boolean.FALSE, obj.get("d"));
        assertTrue(obj.containsKey("e"));
        assertNull(obj.get("e"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void parsesNestedObjectsAndArraysWithoutConfusingTopLevelFields() {
        Map<String, Object> obj = (Map<String, Object>) MiniJson.parse(
                "{\"usage\":{\"session_id\":\"nested-should-be-ignored\",\"list\":[1,2,3]},\"session_id\":\"real-one\"}");

        assertEquals("real-one", obj.get("session_id"));
        Map<String, Object> usage = (Map<String, Object>) obj.get("usage");
        assertEquals(List.of(1.0, 2.0, 3.0), usage.get("list"));
    }

    @Test
    void decodesEscapeSequencesInsideStrings() {
        Object value = MiniJson.parse("\"line1\\nline2\\ttab \\\"quoted\\\" back\\\\slash\"");
        assertEquals("line1\nline2\ttab \"quoted\" back\\slash", value);
    }

    @Test
    void decodesUnicodeEscapes() {
        assertEquals("A", MiniJson.parse("\"\\u0041\""));
    }

    @Test
    void malformedJsonThrowsRatherThanReturningPartialData() {
        assertThrows(RuntimeException.class, () -> MiniJson.parse("{\"a\": }"));
    }
}
