package io.github.khayashi4337.micradrone.chat;

import io.github.khayashi4337.micradrone.build.model.CanonicalJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small recursive-descent JSON parser covering exactly what claude -p's {@code --output-format
 * json} response needs (objects, arrays, strings with escapes, numbers, booleans, null) - no
 * external dependency. Gson ships with NeoForge but isn't on the test sourceSet's runtime
 * classpath (same constraint PlotGeometry documents for net.minecraft.*), and this class exists
 * specifically so JSON parsing stays unit-testable without a real Minecraft/NeoForge runtime.
 * Public so the Minecraft-free build.* core can read and write JSON without a second parser.
 */
public final class MiniJson {
    private final String src;
    private int pos;

    private MiniJson(String src) {
        this.src = src;
    }

    /** Serializes a value built from Map/List/String/Number/Boolean/null back to JSON text. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(value, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(s, sb);
        } else if (value instanceof Boolean b) {
            sb.append(b);
        } else if (value instanceof Number n) {
            sb.append(n.doubleValue() == Math.rint(n.doubleValue()) && !n.toString().contains("E")
                    ? String.valueOf(n.longValue()) : n.toString());
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(String.valueOf(entry.getKey()), sb);
                sb.append(':');
                writeValue(entry.getValue(), sb);
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
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
            throw new IllegalArgumentException("cannot serialize value of type " + value.getClass());
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        CanonicalJson.appendJsonString(s, sb);
    }

    /** Parses a single JSON value (object, array, string, number, boolean, or null). */
    public static Object parse(String json) {
        MiniJson parser = new MiniJson(json);
        parser.skipWhitespace();
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (parser.pos != json.length()) {
            throw new IllegalArgumentException("trailing content after JSON value at " + parser.pos);
        }
        return value;
    }

    private Object parseValue() {
        skipWhitespace();
        char c = peek();
        return switch (c) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't', 'f' -> parseBoolean();
            case 'n' -> parseNull();
            default -> parseNumber();
        };
    }

    private Map<String, Object> parseObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            expect(':');
            Object value = parseValue();
            map.put(key, value);
            skipWhitespace();
            char next = src.charAt(pos++);
            if (next == '}') {
                break;
            }
            if (next != ',') {
                throw new IllegalArgumentException("expected ',' or '}' at " + (pos - 1));
            }
        }
        return map;
    }

    private List<Object> parseArray() {
        List<Object> list = new ArrayList<>();
        expect('[');
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            list.add(parseValue());
            skipWhitespace();
            char next = src.charAt(pos++);
            if (next == ']') {
                break;
            }
            if (next != ',') {
                throw new IllegalArgumentException("expected ',' or ']' at " + (pos - 1));
            }
        }
        return list;
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = src.charAt(pos++);
            if (c == '"') {
                break;
            }
            if (c == '\\') {
                char escaped = src.charAt(pos++);
                sb.append(switch (escaped) {
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'u' -> {
                        char unicode = (char) Integer.parseInt(src.substring(pos, pos + 4), 16);
                        pos += 4;
                        yield unicode;
                    }
                    default -> throw new IllegalArgumentException("bad escape \\" + escaped);
                });
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Boolean parseBoolean() {
        if (src.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("expected boolean at " + pos);
    }

    private Object parseNull() {
        if (src.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw new IllegalArgumentException("expected null at " + pos);
    }

    /** Integer text parses as a {@link Long} so ids and millis beyond 2^53 keep their digits; '.'/'e'/'E' or an overflow give a {@link Double}. */
    private Number parseNumber() {
        int start = pos;
        boolean integer = true;
        while (pos < src.length() && "-+.eE0123456789".indexOf(src.charAt(pos)) >= 0) {
            char c = src.charAt(pos);
            if (c == '.' || c == 'e' || c == 'E') {
                integer = false;
            }
            pos++;
        }
        if (pos == start) {
            throw new IllegalArgumentException("expected value at " + pos);
        }
        String text = src.substring(start, pos);
        if (integer) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException overflow) {
                return Double.parseDouble(text);
            }
        }
        return Double.parseDouble(text);
    }

    private void expect(char c) {
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw new IllegalArgumentException("expected '" + c + "' at " + pos);
        }
        pos++;
    }

    private char peek() {
        if (pos >= src.length()) {
            throw new IllegalArgumentException("unexpected end of JSON");
        }
        return src.charAt(pos);
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }
}
