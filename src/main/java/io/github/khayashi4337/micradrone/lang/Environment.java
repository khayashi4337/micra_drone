package io.github.khayashi4337.micradrone.lang;

import java.util.HashMap;
import java.util.Map;

/**
 * Variable scope for a running script. MVP has no user-defined functions, so
 * if/while/for never introduce new scopes (matches Python semantics) and a
 * single flat map suffices for the whole script execution.
 */
public final class Environment {
    private final Map<String, Object> values = new HashMap<>();

    public void set(String name, Object value) {
        values.put(name, value);
    }

    public Object get(String name, int line) {
        if (!values.containsKey(name)) {
            throw new MicraLangException(line, "undefined variable '" + name + "'");
        }
        return values.get(name);
    }
}
