package io.github.khayashi4337.micradrone.build.parts;

import io.github.khayashi4337.micradrone.build.model.FixHint;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
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
import java.util.regex.Pattern;

/** Fixes the exact type of loosely read values against a {@link ParamSpec} and checks ranges. */
public final class ParamValidator {
    private static final Pattern ROLE = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern BLOCK_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_/.-]+");

    /** Characters below this are control characters. */
    private static final char FIRST_PRINTABLE_CHAR = 0x20;

    // Keys of Issue.data and the fix hint of a rejected parameter (read by the repair prompts).
    private static final String DATA_PARAM = "param";
    private static final String DATA_MIN = "min";
    private static final String DATA_MAX = "max";
    private static final String DATA_ALLOWED = "allowed";
    private static final String HINT_USE_PARAM = "USE_PARAM";
    private static final String HINT_ARG_NAMES = "names";
    private static final String LIST_SEPARATOR = ",";

    private ParamValidator() {
    }

    public record Result(Map<String, ParamValue> typed, List<Issue> issues) {
    }

    /** Control characters other than newline and tab do not survive being written into a script and read back. */
    public static boolean hasForbiddenControl(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < FIRST_PRINTABLE_CHAR && c != '\n' && c != '\t') {
                return true;
            }
        }
        return false;
    }

    public static ParamValue coerce(ParamSpec spec, ParamValue loose) throws ParamException {
        return switch (spec.type()) {
            case INT -> coerceInt(spec, loose);
            case NUM -> coerceNum(spec, loose);
            case BOOL -> {
                if (loose instanceof BoolV b) {
                    yield b;
                }
                throw new ParamException("真偽(True/False)が必要です");
            }
            case STR -> {
                if (!(loose instanceof StrV s)) {
                    throw new ParamException("文字列が必要です");
                }
                if (spec.max() instanceof IntV max && s.value().length() > max.value()) {
                    throw new ParamException(max.value() + "字以内にしてください");
                }
                if (hasForbiddenControl(s.value())) {
                    throw new ParamException("改行(\\n)とタブ以外の制御文字は使えません");
                }
                yield s;
            }
            case ENUM -> {
                String text = loose instanceof StrV s ? s.value() : loose instanceof EnumV e ? e.value() : null;
                if (text == null) {
                    throw new ParamException("次のどれかの文字列が必要です: " + spec.enumValues());
                }
                if (!spec.enumValues().contains(text)) {
                    throw new ParamException("\"" + text + "\"は使えません。使えるのは: " + spec.enumValues());
                }
                yield new EnumV(text);
            }
            case MATERIAL -> {
                String text = loose instanceof StrV s ? s.value() : loose instanceof MaterialV m ? m.value() : null;
                if (text == null || !(ROLE.matcher(text).matches() || BLOCK_ID.matcher(text).matches())) {
                    throw new ParamException("素材は、役割の名前(例: roof)かブロックID(例: minecraft:stone)で指定してください");
                }
                yield new MaterialV(text);
            }
            case INT_LIST -> coerceIntList(spec, loose);
        };
    }

    private static ParamValue coerceInt(ParamSpec spec, ParamValue loose) throws ParamException {
        int value;
        if (loose instanceof IntV i) {
            value = i.value();
        } else if (loose instanceof NumV n && n.value() == Math.rint(n.value())
                && n.value() >= Integer.MIN_VALUE && n.value() <= Integer.MAX_VALUE) {
            value = (int) n.value();
        } else {
            throw new ParamException("整数が必要です");
        }
        checkRange(spec, value);
        return new IntV(value);
    }

    private static ParamValue coerceNum(ParamSpec spec, ParamValue loose) throws ParamException {
        double value;
        if (loose instanceof IntV i) {
            value = i.value();
        } else if (loose instanceof NumV n) {
            value = n.value();
        } else {
            throw new ParamException("数が必要です");
        }
        boolean tooLow = spec.min() != null && value < asDouble(spec.min());
        boolean tooHigh = spec.max() != null && value > asDouble(spec.max());
        if (tooLow || tooHigh) {
            throw new ParamException(rangeText(spec));
        }
        return new NumV(value);
    }

    private static ParamValue coerceIntList(ParamSpec spec, ParamValue loose) throws ParamException {
        if (!(loose instanceof ListV list)) {
            throw new ParamException("整数の並び(例: [1, 2])が必要です");
        }
        if (spec.maxItems() > 0 && list.value().size() > spec.maxItems()) {
            throw new ParamException("要素は" + spec.maxItems() + "個までです");
        }
        List<ParamValue> out = new ArrayList<>();
        for (ParamValue item : list.value()) {
            out.add(coerceInt(spec, item));
        }
        return new ListV(out);
    }

    private static void checkRange(ParamSpec spec, int value) throws ParamException {
        boolean tooLow = spec.min() instanceof IntV min && value < min.value();
        boolean tooHigh = spec.max() instanceof IntV max && value > max.value();
        if (tooLow || tooHigh) {
            throw new ParamException(rangeText(spec));
        }
    }

    /** Names the bounds that exist: a range when both do, otherwise only the one that is there. */
    private static String rangeText(ParamSpec spec) {
        if (spec.min() != null && spec.max() != null) {
            return bound(spec.min()) + "〜" + bound(spec.max()) + "の範囲にしてください";
        }
        if (spec.min() != null) {
            return bound(spec.min()) + "以上にしてください";
        }
        return bound(spec.max()) + "以下にしてください";
    }

    private static String bound(ParamValue v) {
        return String.valueOf(v.toTree());
    }

    private static double asDouble(ParamValue v) {
        return v instanceof IntV i ? i.value() : v instanceof NumV n ? n.value() : 0;
    }

    /** Types every given value; unknown names, wrong types, out-of-range values and missing required ones are E-PARAM-RANGE. */
    public static Result validate(String nodeId, PartType type, Map<String, ParamValue> given) {
        Map<String, ParamValue> typed = new TreeMap<>();
        List<Issue> issues = new ArrayList<>();
        for (Map.Entry<String, ParamValue> e : given.entrySet()) {
            String name = e.getKey();
            ParamSpec spec = type.param(name).orElse(null);
            if (spec == null) {
                List<String> known = type.params().stream().map(ParamSpec::name).toList();
                issues.add(Issue.of(IssueCode.E_PARAM_RANGE, name, List.of(nodeId),
                        type.id() + "に「" + name + "」というパラメータはありません(使えるのは " + known + ")",
                        Map.of(DATA_PARAM, name),
                        List.of(new FixHint(HINT_USE_PARAM, Map.of(HINT_ARG_NAMES, String.join(LIST_SEPARATOR, known))))));
                continue;
            }
            try {
                typed.put(name, coerce(spec, e.getValue()));
            } catch (ParamException ex) {
                issues.add(Issue.of(IssueCode.E_PARAM_RANGE, name, List.of(nodeId),
                        type.id() + "の「" + name + "」: " + ex.getMessage(), rejectionData(spec), List.of()));
            }
        }
        for (ParamSpec spec : type.params()) {
            if (spec.required() && !given.containsKey(spec.name())) {
                issues.add(Issue.of(IssueCode.E_PARAM_RANGE, spec.name(), List.of(nodeId),
                        type.id() + "の「" + spec.name() + "」は必須です", Map.of(DATA_PARAM, spec.name()), List.of()));
            }
        }
        return new Result(typed, issues);
    }

    /** What a repair prompt needs to know about the parameter that was rejected: its bounds and allowed values. */
    private static Map<String, String> rejectionData(ParamSpec spec) {
        Map<String, String> data = new TreeMap<>();
        data.put(DATA_PARAM, spec.name());
        if (spec.min() != null) {
            data.put(DATA_MIN, bound(spec.min()));
        }
        if (spec.max() != null) {
            data.put(DATA_MAX, bound(spec.max()));
        }
        if (!spec.enumValues().isEmpty()) {
            data.put(DATA_ALLOWED, String.join(LIST_SEPARATOR, spec.enumValues()));
        }
        return data;
    }
}
