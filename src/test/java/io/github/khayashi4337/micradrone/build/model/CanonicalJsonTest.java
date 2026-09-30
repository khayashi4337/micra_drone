package io.github.khayashi4337.micradrone.build.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {
    @Test
    void keysAreSortedAndNoWhitespace() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("b", 1);
        m.put("a", List.of(2L, "x"));
        assertEquals("{\"a\":[2,\"x\"],\"b\":1}", CanonicalJson.write(m));
    }

    @Test
    void numbersAreIntegersOrPlainDecimals() {
        assertEquals("2", CanonicalJson.write(2.0));
        assertEquals("2.5", CanonicalJson.write(2.5));
        assertEquals("0.1", CanonicalJson.write(0.1));
        assertEquals("0", CanonicalJson.write(-0.0));
        assertEquals("1000", CanonicalJson.write(1000.0));
        assertEquals("-7", CanonicalJson.write(-7));
        assertEquals("12.5", CanonicalJson.write(new BigDecimal("12.500")));
        assertEquals("3", CanonicalJson.write(3L));
        assertEquals("5", CanonicalJson.write((short) 5));
        assertEquals("6", CanonicalJson.write((byte) 6));
        // a Float keeps its own decimal value instead of widening to the nearest double
        assertEquals("0.1", CanonicalJson.write(0.1f));
        assertEquals("1.25", CanonicalJson.write(1.25f));
    }

    @Test
    void nonFiniteNumbersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(Double.POSITIVE_INFINITY));
    }

    @Test
    void stringsAreEscapedAndUnicodeIsKept() {
        assertEquals("\"a\\\"b\\\\c\\n\\t\"", CanonicalJson.write("a\"b\\c\n\t"));
        assertEquals("\"a\\bb\\fc\"", CanonicalJson.write("a\bb\fc"));
        assertEquals("\"\\u0001\"", CanonicalJson.write("\u0001"));
        // a lone surrogate is escaped so it cannot hash identically to '?'
        assertEquals("\"\\ud800\"", CanonicalJson.write("\uD800"));
        assertEquals("\"x\\udc00\"", CanonicalJson.write("x\uDC00"));
        assertEquals("\"屋根🏠\"", CanonicalJson.write("屋根🏠"));
    }

    @Test
    void setsAreOrderedByTheirCanonicalForm() {
        Set<String> s = new TreeSet<>(List.of("b", "a"));
        assertEquals("[\"a\",\"b\"]", CanonicalJson.write(s));
        assertEquals("[\"a\",\"b\"]", CanonicalJson.write(Set.of("b", "a")));
    }

    @Test
    void nullBooleanAndNesting() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", null);
        m.put("t", true);
        m.put("o", Map.of("z", 1, "y", 2));
        assertEquals("{\"n\":null,\"o\":{\"y\":2,\"z\":1},\"t\":true}", CanonicalJson.write(m));
    }

    @Test
    void unsupportedTypesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(new Object()));
        Map<Object, Object> badKey = new LinkedHashMap<>();
        badKey.put(1, "x");
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(badKey));
        // a collection that is neither List nor Set has no defined wire order
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(new java.util.ArrayDeque<>(List.of(1))));
    }

    @Test
    void miniJsonParsedTreesCanBeWrittenAndAreOrderIndependent() {
        Object a = MiniJson.parse("{\"b\":1,\"a\":{\"d\":2.5,\"c\":[1,2]}}");
        Object b = MiniJson.parse("{ \"a\" : {\"c\":[1,2], \"d\":2.5}, \"b\":1 }");
        assertEquals(CanonicalJson.write(a), CanonicalJson.write(b));
        assertEquals("{\"a\":{\"c\":[1,2],\"d\":2.5},\"b\":1}", CanonicalJson.write(a));
    }
}
