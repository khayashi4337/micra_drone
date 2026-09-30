package io.github.khayashi4337.micradrone.build.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** Typed access to trees produced by {@code MiniJson.parse}, with a path in every error message. */
final class JsonTree {
    /** The path of the document root; every error path starts with it. */
    static final String ROOT_PATH = "$";

    private static final String MESSAGE_SEPARATOR = ": ";
    private static final String KEY_SEPARATOR = ".";
    private static final String INDEX_OPEN = "[";
    private static final String INDEX_CLOSE = "]";

    private JsonTree() {
    }

    /** The path of member {@code key} of the object at {@code path}, e.g. {@code $.nodes[2].anchor}. */
    static String child(String path, String key) {
        return path + KEY_SEPARATOR + key;
    }

    /** The path of element {@code index} of the array at {@code path}. */
    static String item(String path, int index) {
        return path + INDEX_OPEN + index + INDEX_CLOSE;
    }

    static PlanJsonException bad(String path, String message) {
        return new PlanJsonException(path + MESSAGE_SEPARATOR + message);
    }

    /** True when the number is a whole number that fits an {@code int} (false for NaN and infinities). */
    static boolean isIntValued(double d) {
        return d == Math.rint(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object value, String path) {
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw bad(path, "expected an object");
    }

    @SuppressWarnings("unchecked")
    static List<Object> arr(Object value, String path) {
        if (value instanceof List<?> l) {
            return (List<Object>) l;
        }
        throw bad(path, "expected an array");
    }

    static String str(Object value, String path) {
        if (value instanceof String s) {
            return s;
        }
        throw bad(path, "expected a string");
    }

    static boolean bool(Object value, String path) {
        if (value instanceof Boolean b) {
            return b;
        }
        throw bad(path, "expected true or false");
    }

    static int integer(Object value, String path) {
        if (value instanceof Number n && isIntValued(n.doubleValue())) {
            return (int) n.doubleValue();
        }
        throw bad(path, "expected an integer");
    }

    /** A whole number read as a {@code long}, for values such as millis that do not fit an {@code int}. */
    static long longInteger(Object value, String path) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE) {
                return n.longValue();
            }
        }
        throw bad(path, "expected an integer");
    }

    static double number(Object value, String path) {
        if (value instanceof Number n && Double.isFinite(n.doubleValue())) {
            return n.doubleValue();
        }
        throw bad(path, "expected a finite number");
    }

    /** The member {@code key}, which must be present (its value may still be JSON null). */
    static Object req(Map<String, Object> map, String key, String path) {
        if (!map.containsKey(key)) {
            throw bad(path, "missing \"" + key + "\"");
        }
        return map.get(key);
    }

    static String reqStr(Map<String, Object> map, String key, String path) {
        return str(req(map, key, path), child(path, key));
    }

    static int reqInt(Map<String, Object> map, String key, String path) {
        return integer(req(map, key, path), child(path, key));
    }

    static boolean reqBool(Map<String, Object> map, String key, String path) {
        return bool(req(map, key, path), child(path, key));
    }

    /** Reads the required member {@code key} with {@code read}, giving it its own path. */
    static <T> T req(Map<String, Object> map, String key, String path, BiFunction<Object, String, T> read) {
        return read.apply(req(map, key, path), child(path, key));
    }

    /** Null when the key is absent or JSON null. */
    static String optStr(Map<String, Object> map, String key, String path) {
        Object v = map.get(key);
        return v == null ? null : str(v, child(path, key));
    }

    /** Null when the key is absent or JSON null. */
    static Integer optInt(Map<String, Object> map, String key, String path) {
        Object v = map.get(key);
        return v == null ? null : integer(v, child(path, key));
    }

    /** Null when the key is absent or JSON null. */
    static Boolean optBool(Map<String, Object> map, String key, String path) {
        Object v = map.get(key);
        return v == null ? null : bool(v, child(path, key));
    }

    /** Reads member {@code key} with {@code read}; null when the key is absent or JSON null. */
    static <T> T opt(Map<String, Object> map, String key, String path, BiFunction<Object, String, T> read) {
        Object v = map.get(key);
        return v == null ? null : read.apply(v, child(path, key));
    }

    /** Reads every element of the array {@code value} with {@code read}, giving each its own path. */
    static <T> List<T> list(Object value, String path, BiFunction<Object, String, T> read) {
        List<Object> raw = arr(value, path);
        List<T> out = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            out.add(read.apply(raw.get(i), item(path, i)));
        }
        return out;
    }

    /** The array member {@code key}, which must be present, read element by element. */
    static <T> List<T> reqList(Map<String, Object> map, String key, String path, BiFunction<Object, String, T> read) {
        return list(req(map, key, path), child(path, key), read);
    }

    /** The array member {@code key}, read element by element; empty when the key is absent or JSON null. */
    static <T> List<T> optList(Map<String, Object> map, String key, String path, BiFunction<Object, String, T> read) {
        Object v = map.get(key);
        return v == null ? new ArrayList<>() : list(v, child(path, key), read);
    }
}
