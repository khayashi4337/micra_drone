package io.github.khayashi4337.micradrone.build.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A part parameter value. Values arriving from JSON or scripts are first read "loosely" ({@link #fromTree});
 * the part registry then fixes the exact type from the parameter's spec, so the stored form is canonical.
 */
public sealed interface ParamValue {
    record IntV(int value) implements ParamValue {
    }

    record NumV(double value) implements ParamValue {
        public NumV {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("number must be finite: " + value);
            }
        }
    }

    record BoolV(boolean value) implements ParamValue {
    }

    record StrV(String value) implements ParamValue {
        public StrV {
            Objects.requireNonNull(value, "value");
        }
    }

    record EnumV(String value) implements ParamValue {
        public EnumV {
            Objects.requireNonNull(value, "value");
        }
    }

    /** Either a palette role name or a block id (see StyleSpec). */
    record MaterialV(String value) implements ParamValue {
        public MaterialV {
            Objects.requireNonNull(value, "value");
        }
    }

    record ListV(List<ParamValue> value) implements ParamValue {
        public ListV {
            value = List.copyOf(value);
        }
    }

    /** The JSON-tree form: Long, Double, Boolean, String or List. Enum and material values are plain strings. */
    default Object toTree() {
        return switch (this) {
            case IntV v -> (long) v.value();
            case NumV v -> v.value();
            case BoolV v -> v.value();
            case StrV v -> v.value();
            case EnumV v -> v.value();
            case MaterialV v -> v.value();
            case ListV v -> {
                List<Object> out = new ArrayList<>();
                for (ParamValue item : v.value()) {
                    out.add(item.toTree());
                }
                yield out;
            }
        };
    }

    /**
     * Reads a JSON/script value without knowing the spec: an integral number within int range is an
     * {@link IntV}, any other finite number a {@link NumV}, a string a {@link StrV}. NaN and infinities are
     * refused by the {@link NumV} constructor; maps and null are unsupported.
     */
    static ParamValue fromTree(Object tree) {
        if (tree instanceof Boolean b) {
            return new BoolV(b);
        }
        if (tree instanceof String s) {
            return new StrV(s);
        }
        if (tree instanceof Number n) {
            double d = n.doubleValue();
            return JsonTree.isIntValued(d) ? new IntV((int) d) : new NumV(d);
        }
        if (tree instanceof List<?> list) {
            List<ParamValue> items = new ArrayList<>();
            for (Object item : list) {
                items.add(fromTree(item));
            }
            return new ListV(items);
        }
        throw new IllegalArgumentException("unsupported parameter value: "
                + (tree == null ? "null" : tree.getClass().getSimpleName()));
    }
}
