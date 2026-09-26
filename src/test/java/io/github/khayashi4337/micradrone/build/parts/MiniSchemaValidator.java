package io.github.khayashi4337.micradrone.build.parts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A tiny JSON Schema checker for the keywords the generated schemas use; enough to test them without a library.
 * Anything outside the supported keyword set is an error, so a typo in the generator cannot pass silently.
 */
final class MiniSchemaValidator {
    /** The only keywords {@link SchemaGenerator} emits; any other key in a schema object is reported. */
    private static final Set<String> KNOWN_KEYWORDS = Set.of(
            "$defs", "$ref", "type", "properties", "required", "additionalProperties", "oneOf", "const", "enum",
            "minimum", "maximum", "maxLength", "pattern", "items", "minItems", "maxItems");
    private static final String DEFS_PREFIX = "#/$defs/";

    private final Map<String, Object> root;

    MiniSchemaValidator(Map<String, Object> root) {
        this.root = root;
    }

    List<String> validate(Object value) {
        List<String> errors = new ArrayList<>();
        check(root, value, "$", errors);
        return errors;
    }

    @SuppressWarnings("unchecked")
    private void check(Object schemaObj, Object v, String path, List<String> errs) {
        Map<String, Object> schema = (Map<String, Object>) schemaObj;
        boolean known = true;
        for (String keyword : schema.keySet()) {
            if (!KNOWN_KEYWORDS.contains(keyword)) {
                errs.add(path + ": unknown schema keyword " + keyword);
                known = false;
            }
        }
        if (!known) {
            return;
        }
        if (schema.containsKey("$ref")) {
            String ref = (String) schema.get("$ref");
            Map<String, Object> defs = (Map<String, Object>) root.get("$defs");
            check(defs.get(ref.substring(DEFS_PREFIX.length())), v, path, errs);
            return;
        }
        if (schema.containsKey("oneOf")) {
            int matches = 0;
            for (Object branch : (List<Object>) schema.get("oneOf")) {
                List<String> e = new ArrayList<>();
                check(branch, v, path, e);
                if (e.isEmpty()) {
                    matches++;
                }
            }
            if (matches != 1) {
                errs.add(path + ": must match exactly one schema in oneOf (matched " + matches + ")");
            }
            return;
        }
        if (schema.containsKey("const") && !same(schema.get("const"), v)) {
            errs.add(path + ": must equal constant " + schema.get("const"));
            return;
        }
        if (schema.containsKey("enum")) {
            boolean found = false;
            for (Object o : (List<Object>) schema.get("enum")) {
                found |= same(o, v);
            }
            if (!found) {
                errs.add(path + ": not one of the allowed values: " + v);
            }
        }
        if (schema.containsKey("type") && !typeOk(schema.get("type"), v)) {
            errs.add(path + ": wrong type for " + v);
            return;
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (schema.get("minimum") instanceof Number min && d < min.doubleValue()) {
                errs.add(path + ": must be >= " + min);
            }
            if (schema.get("maximum") instanceof Number max && d > max.doubleValue()) {
                errs.add(path + ": must be <= " + max);
            }
        }
        if (v instanceof String s) {
            if (schema.get("maxLength") instanceof Number max && s.length() > max.intValue()) {
                errs.add(path + ": too long");
            }
            if (schema.get("pattern") instanceof String p && !Pattern.compile(p).matcher(s).find()) {
                errs.add(path + ": does not match pattern " + p);
            }
        }
        if (v instanceof List<?> list) {
            if (schema.get("minItems") instanceof Number min && list.size() < min.intValue()) {
                errs.add(path + ": too few items");
            }
            if (schema.get("maxItems") instanceof Number max && list.size() > max.intValue()) {
                errs.add(path + ": too many items");
            }
            if (schema.get("items") != null) {
                for (int i = 0; i < list.size(); i++) {
                    check(schema.get("items"), list.get(i), path + "[" + i + "]", errs);
                }
            }
        }
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> props = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
            if (schema.get("required") instanceof List<?> req) {
                for (Object r : req) {
                    if (!map.containsKey(r)) {
                        errs.add(path + ": missing required " + r);
                    }
                }
            }
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = (String) e.getKey();
                if (props.containsKey(key)) {
                    check(props.get(key), e.getValue(), path + "." + key, errs);
                } else if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                    errs.add(path + ": additional property " + key + " is not allowed");
                } else if (schema.get("additionalProperties") instanceof Map<?, ?>) {
                    check(schema.get("additionalProperties"), e.getValue(), path + "." + key, errs);
                }
            }
        }
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return x.doubleValue() == y.doubleValue();
        }
        return a == null ? b == null : a.equals(b);
    }

    private static boolean typeOk(Object type, Object v) {
        if (type instanceof List<?> types) {
            for (Object t : types) {
                if (typeOk(t, v)) {
                    return true;
                }
            }
            return false;
        }
        return switch ((String) type) {
            case "object" -> v instanceof Map;
            case "array" -> v instanceof List;
            case "string" -> v instanceof String;
            case "boolean" -> v instanceof Boolean;
            case "null" -> v == null;
            case "integer" -> v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue());
            case "number" -> v instanceof Number;
            default -> false;
        };
    }
}
