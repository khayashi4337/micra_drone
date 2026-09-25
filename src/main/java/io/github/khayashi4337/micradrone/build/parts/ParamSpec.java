package io.github.khayashi4337.micradrone.build.parts;

import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.ParamValue.BoolV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.EnumV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.IntV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.ListV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.MaterialV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.StrV;
import java.util.List;
import java.util.Objects;

/**
 * One parameter of a part: name, type, range, default. A null default means the parameter is required.
 * For STR the {@code max} is the maximum length; for INT_LIST {@code min}/{@code max} bound each element and
 * {@code maxItems} bounds the length.
 */
public record ParamSpec(String name, ParamType type, String unit, ParamValue min, ParamValue max,
                        ParamValue defaultValue, List<String> enumValues, int maxItems) {
    private static final String NO_UNIT = "";
    /** {@code maxItems} of a parameter that is not a list. */
    private static final int NOT_A_LIST = 0;

    public ParamSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        unit = Objects.requireNonNullElse(unit, NO_UNIT);
        enumValues = List.copyOf(Objects.requireNonNullElse(enumValues, List.of()));
        if (type == ParamType.ENUM && enumValues.isEmpty()) {
            throw new IllegalArgumentException("enum parameter " + name + " needs values");
        }
    }

    public boolean required() {
        return defaultValue == null;
    }

    public static ParamSpec integer(String name, int min, int max, Integer defaultValue) {
        return new ParamSpec(name, ParamType.INT, NO_UNIT, new IntV(min), new IntV(max),
                defaultValue == null ? null : new IntV(defaultValue), List.of(), NOT_A_LIST);
    }

    public static ParamSpec bool(String name, boolean defaultValue) {
        return new ParamSpec(name, ParamType.BOOL, NO_UNIT, null, null, new BoolV(defaultValue), List.of(), NOT_A_LIST);
    }

    public static ParamSpec enumOf(String name, String defaultValue, String... values) {
        return new ParamSpec(name, ParamType.ENUM, NO_UNIT, null, null,
                defaultValue == null ? null : new EnumV(defaultValue), List.of(values), NOT_A_LIST);
    }

    public static ParamSpec material(String name, String defaultRole) {
        return new ParamSpec(name, ParamType.MATERIAL, NO_UNIT, null, null, new MaterialV(defaultRole), List.of(),
                NOT_A_LIST);
    }

    public static ParamSpec text(String name, int maxLength, String defaultValue) {
        return new ParamSpec(name, ParamType.STR, NO_UNIT, null, new IntV(maxLength),
                defaultValue == null ? null : new StrV(defaultValue), List.of(), NOT_A_LIST);
    }

    public static ParamSpec intList(String name, int min, int max, int maxItems) {
        return new ParamSpec(name, ParamType.INT_LIST, NO_UNIT, new IntV(min), new IntV(max), new ListV(List.of()),
                List.of(), maxItems);
    }
}
