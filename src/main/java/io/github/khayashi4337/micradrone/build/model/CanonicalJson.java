package io.github.khayashi4337.micradrone.build.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The canonical JSON form used for hashing: keys in dictionary order, integers as integers, other numbers as
 * plain decimals without trailing zeros, no whitespace, sets in the dictionary order of their elements' own
 * canonical form. Two structurally equal trees always produce the same bytes.
 */
public final class CanonicalJson {
    private static final char FIRST_PRINTABLE_CHAR = 0x20;
    private static final String UNICODE_ESCAPE_FORMAT = "\\u%04x";

    private CanonicalJson() {
    }

    public static String write(Object tree) {
        StringBuilder sb = new StringBuilder();
        writeValue(tree, sb);
        return sb.toString();
    }

    private static void writeValue(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(s, sb);
        } else if (value instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            sb.append(((Number) value).longValue());
        } else if (value instanceof BigDecimal bd) {
            sb.append(plain(bd));
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (!Double.isFinite(d)) {
                throw new IllegalArgumentException("non-finite number cannot be written canonically: " + d);
            }
            sb.append(plain(BigDecimal.valueOf(d)));
        } else if (value instanceof Map<?, ?> map) {
            writeMap(map, sb);
        } else if (value instanceof Set<?> set) {
            List<String> parts = new ArrayList<>();
            for (Object item : set) {
                StringBuilder one = new StringBuilder();
                writeValue(item, one);
                parts.add(one.toString());
            }
            parts.sort(null);
            sb.append('[').append(String.join(",", parts)).append(']');
        } else if (value instanceof Collection<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(item, sb);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("cannot serialize value of type " + value.getClass().getName());
        }
    }

    private static void writeMap(Map<?, ?> map, StringBuilder sb) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String key)) {
                throw new IllegalArgumentException("object keys must be strings but was " + e.getKey());
            }
            sorted.put(key, e.getValue());
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(e.getKey(), sb);
            sb.append(':');
            writeValue(e.getValue(), sb);
        }
        sb.append('}');
    }

    /** toPlainString never uses an exponent, and stripTrailingZeros makes 2.0 and 2 the same text. */
    private static String plain(BigDecimal value) {
        BigDecimal stripped = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        return stripped.toPlainString();
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < FIRST_PRINTABLE_CHAR) {
                        sb.append(String.format(UNICODE_ESCAPE_FORMAT, (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
