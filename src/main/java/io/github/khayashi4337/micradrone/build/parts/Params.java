package io.github.khayashi4337.micradrone.build.parts;

import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.ParamValue.BoolV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.EnumV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.IntV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.ListV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.MaterialV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.NumV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.StrV;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Typed parameters with defaults filled in; what a generator reads. */
public final class Params {
    private final Map<String, ParamValue> values;

    private Params(Map<String, ParamValue> values) {
        this.values = values;
    }

    public static Params resolve(PartType type, Map<String, ParamValue> typed) {
        Map<String, ParamValue> all = new TreeMap<>();
        for (ParamSpec spec : type.params()) {
            ParamValue v = typed.get(spec.name());
            if (v != null) {
                all.put(spec.name(), v);
            } else if (spec.defaultValue() != null) {
                all.put(spec.name(), spec.defaultValue());
            }
        }
        return new Params(all);
    }

    private ParamValue get(String name) {
        ParamValue v = values.get(name);
        if (v == null) {
            throw new IllegalArgumentException("parameter not available: " + name);
        }
        return v;
    }

    public int i(String name) {
        if (get(name) instanceof IntV v) {
            return v.value();
        }
        throw new IllegalArgumentException(name + " is not an integer");
    }

    public double d(String name) {
        ParamValue v = get(name);
        if (v instanceof NumV n) {
            return n.value();
        }
        if (v instanceof IntV i) {
            return i.value();
        }
        throw new IllegalArgumentException(name + " is not a number");
    }

    public boolean b(String name) {
        if (get(name) instanceof BoolV v) {
            return v.value();
        }
        throw new IllegalArgumentException(name + " is not a boolean");
    }

    /** The text of a string, enum or material value. */
    public String s(String name) {
        ParamValue v = get(name);
        if (v instanceof StrV s) {
            return s.value();
        }
        if (v instanceof EnumV e) {
            return e.value();
        }
        if (v instanceof MaterialV m) {
            return m.value();
        }
        throw new IllegalArgumentException(name + " is not text");
    }

    public List<Integer> ints(String name) {
        if (get(name) instanceof ListV list) {
            List<Integer> out = new ArrayList<>();
            for (ParamValue item : list.value()) {
                out.add(((IntV) item).value());
            }
            return out;
        }
        throw new IllegalArgumentException(name + " is not a list");
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }
}
