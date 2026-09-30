package io.github.khayashi4337.micradrone.construction.core;

import java.util.List;
import java.util.Map;

/**
 * Shape checks for a parsed JSON tree ({@code chat.MiniJson} types a value only as Object: integer text reads as
 * {@code Long}, decimal text as {@code Double}, so "is an integer" is a check here, not a cast). A value of the
 * wrong shape is an {@link IllegalArgumentException}, never silently coerced.
 */
public final class JsonReads {
    /** Whole {@code double} bounds of a {@code long}: 2^63 itself does not fit, so the upper bound is exclusive. */
    private static final double MIN_LONG_AS_DOUBLE = -0x1.0p63;
    private static final double MAX_LONG_EXCLUSIVE = 0x1.0p63;

    private JsonReads() {
    }

    public static int integer(Object value, String what) {
        long l = longValue(value, what);
        if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(what + " is not an int: " + l);
        }
        return (int) l;
    }

    public static long longValue(Object value, String what) {
        if (!(value instanceof Number n)) {
            throw new IllegalArgumentException(what + " is not a number: " + describe(value));
        }
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (d != Math.rint(d) || d < MIN_LONG_AS_DOUBLE || d >= MAX_LONG_EXCLUSIVE) {
                throw new IllegalArgumentException(what + " is not an integer: " + n);
            }
            return (long) d;
        }
        return n.longValue();
    }

    public static String string(Object value, String what) {
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException(what + " is not a string: " + describe(value));
        }
        return s;
    }

    public static String stringOrNull(Object value, String what) {
        return value == null ? null : string(value, what);
    }

    public static boolean bool(Object value, String what) {
        if (!(value instanceof Boolean b)) {
            throw new IllegalArgumentException(what + " is not a boolean: " + describe(value));
        }
        return b;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value, String what) {
        if (!(value instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException(what + " is not an object: " + describe(value));
        }
        for (Object key : m.keySet()) {
            if (!(key instanceof String)) {
                throw new IllegalArgumentException(what + " has a non-string key: " + key);
            }
        }
        return (Map<String, Object>) m;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object value, String what) {
        if (!(value instanceof List<?> l)) {
            throw new IllegalArgumentException(what + " is not an array: " + describe(value));
        }
        return (List<Object>) l;
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName() + " " + value;
    }
}
