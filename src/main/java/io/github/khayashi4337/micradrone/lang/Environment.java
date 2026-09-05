package io.github.khayashi4337.micradrone.lang;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Variable scope for a running script. if/while/for never introduce new scopes (matches Python
 * semantics), but a user-defined function call does: it gets a fresh frame whose parent is always
 * the module-level (global) frame, never the caller's locals - functions are not closures over
 * enclosing locals, only over the global scope, an intentional simplification (see
 * docs/design/lang_def_return_break_continue.md). {@link #set} always binds in the current frame
 * (Python assignment semantics, no {@code global} statement), while {@link #get}/{@link #tryGet}
 * walk outward through parent frames.
 */
public final class Environment {
    private final Environment parent;
    private final Map<String, Object> values = new HashMap<>();

    /** A root/global environment, with no parent. */
    public Environment() {
        this.parent = null;
    }

    /** A child frame (e.g. a function call) that falls back to {@code parent} for reads. */
    public Environment(Environment parent) {
        this.parent = parent;
    }

    /**
     * @throws NullPointerException if {@code value} is the Java {@code null} reference - every
     *     call site should already be passing a real language value (see {@link #tryGet}'s doc for
     *     why {@code null} is reserved to mean "unbound"); this fails fast at the point a bad value
     *     would first be introduced, rather than surfacing later as a baffling "undefined variable"
     *     when something merely tries to read it back.
     */
    public void set(String name, Object value) {
        values.put(name, Objects.requireNonNull(value, "value"));
    }

    public Object get(String name, int line) {
        Object value = tryGet(name);
        if (value == null) {
            throw new MicraLangException(line, "undefined variable '" + name + "'");
        }
        return value;
    }

    /**
     * Same lookup as {@link #get}, but returns {@code null} instead of throwing when unbound.
     * Safe because no script-visible value is ever the Java {@code null} reference - the
     * language's None is the {@link MicraNone#INSTANCE} singleton - so a {@code null} here
     * unambiguously means "not bound in this frame or any parent".
     */
    public Object tryGet(String name) {
        Environment env = this;
        while (env != null) {
            Object value = env.values.get(name);
            if (value != null) {
                return value;
            }
            env = env.parent;
        }
        return null;
    }
}
