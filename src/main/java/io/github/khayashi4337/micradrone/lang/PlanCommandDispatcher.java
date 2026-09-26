package io.github.khayashi4337.micradrone.lang;

import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns an evaluated construction command call into a typed {@link PlanApi} call. Argument problems become
 * {@link MicraLangException}s that name the command and the line. Arguments are read in order through an
 * {@link ArgReader}, so a type error names the argument's position without each command repeating it.
 */
final class PlanCommandDispatcher {
    /** How many arguments a command takes (inclusive range). */
    private record Arity(int min, int max) {
        String describe() {
            return min == max ? String.valueOf(min) : min + " to " + max;
        }
    }

    // Arities from 04_foundations.md F-6: required arguments first, optional ones trailing.
    private static final Arity SITE_ARITY = new Arity(6, 8);
    private static final Arity STYLE_ARITY = new Arity(2, 2);
    private static final Arity MOOD_ARITY = new Arity(1, 1);
    private static final Arity PART_ARITY = new Arity(5, 7);
    private static final Arity UPDATE_PARAMS_ARITY = new Arity(2, 2);
    private static final Arity RELOCATE_ARITY = new Arity(2, 2);
    private static final Arity REMOVE_PART_ARITY = new Arity(1, 1);
    private static final Arity CONNECT_ARITY = new Arity(4, 6);
    private static final Arity DISCONNECT_ARITY = new Arity(1, 1);
    private static final Arity LOGISTICS_ARITY = new Arity(3, 3);
    /** {@code <part>(id, parent, anchor, params[, tags[, label]])}: {@code part(...)} without its type argument. */
    private static final Arity PART_COMMAND_ARITY = new Arity(4, 6);

    /** {@code [x0, y0, z0, x1, y1, z1]}. */
    private static final int SITE_BOUNDS_SIZE = 6;
    /** What an omitted {@code terrain_digest}, {@code claim_id} or {@code label} becomes. */
    private static final String ABSENT_TEXT = "";

    private static final String ANCHOR_SURFACE = "surface";
    private static final String ANCHOR_SLOT = "slot";
    private static final String SIDE_OUTER = "outer";
    private static final String SIDE_INNER = "inner";
    /** {@code ["surface", target, side, u, v]}. */
    private static final int SURFACE_ANCHOR_SIZE = 5;
    /** {@code ["slot", slot]} up to {@code ["slot", slot, turns, mirror]}. */
    private static final int SLOT_ANCHOR_MIN_SIZE = 2;
    private static final int SLOT_ANCHOR_MAX_SIZE = 4;
    /** {@code [u, v, w]} up to {@code [u, v, w, turns, mirror]}. */
    private static final int ABSOLUTE_ANCHOR_MIN_SIZE = 3;
    private static final int ABSOLUTE_ANCHOR_MAX_SIZE = 5;
    /** Quarter turns: 0 to 3. */
    private static final int MAX_TURNS = 3;

    private static final List<String> FACINGS = Arrays.stream(Facing.values()).map(Facing::lower).toList();
    private static final String FACINGS_QUOTED = FACINGS.stream().map(f -> "\"" + f + "\"").collect(Collectors.joining(" "));
    private static final char NODE_PORT_SEPARATOR = '.';

    private PlanCommandDispatcher() {
    }

    static Object invoke(PlanApi api, String name, List<Object> args, int line) {
        try {
            return dispatch(api, name, args, line);
        } catch (IllegalArgumentException e) {
            throw new MicraLangException(line, name + "(): " + e.getMessage());
        }
    }

    private static Object dispatch(PlanApi api, String name, List<Object> args, int line) {
        ArgReader r = new ArgReader(args);
        switch (name) {
            case CommandNames.SITE -> {
                arity(name, args, SITE_ARITY, line);
                api.site(r.string(), integer(r.next()), integer(r.next()), integer(r.next()), facing(r.string()),
                        ints(r.next(), SITE_BOUNDS_SIZE), r.optionalString(), r.optionalString());
            }
            case CommandNames.STYLE -> {
                arity(name, args, STYLE_ARITY, line);
                api.style(r.string(), r.string());
            }
            case CommandNames.MOOD -> {
                arity(name, args, MOOD_ARITY, line);
                api.mood(r.string());
            }
            case CommandNames.PART -> {
                arity(name, args, PART_ARITY, line);
                return part(api, r.string(), r.string(), r);
            }
            case CommandNames.UPDATE_PARAMS -> {
                arity(name, args, UPDATE_PARAMS_ARITY, line);
                api.updateParams(r.string(), map(r.next()));
            }
            case CommandNames.RELOCATE -> {
                arity(name, args, RELOCATE_ARITY, line);
                api.relocate(r.string(), anchor(r.next()));
            }
            case CommandNames.REMOVE_PART -> {
                arity(name, args, REMOVE_PART_ARITY, line);
                api.removePart(r.string());
            }
            case CommandNames.CONNECT -> {
                arity(name, args, CONNECT_ARITY, line);
                String id = r.string();
                String from = nodePort(r.string());
                String to = nodePort(r.string());
                String kind = r.string();
                // None or omitted means automatic routing; any list (even an empty one) means explicit routing.
                Object via = r.optional();
                Object constraints = r.optional();
                api.connect(id, from, to, kind, via instanceof MicraNone ? null : strings(via),
                        constraints instanceof MicraNone ? null : map(constraints));
            }
            case CommandNames.DISCONNECT -> {
                arity(name, args, DISCONNECT_ARITY, line);
                api.disconnect(r.string());
            }
            case CommandNames.LOGISTICS -> {
                arity(name, args, LOGISTICS_ARITY, line);
                api.logistics(list(r.next()), list(r.next()), list(r.next()));
            }
            default -> {
                if (!CommandNames.PLAN_PART_COMMANDS.contains(name)) {
                    throw new MicraLangException(line, "unknown function '" + name + "'");
                }
                arity(name, args, PART_COMMAND_ARITY, line);
                return part(api, r.string(), BuildingParts.ID_PREFIX + name, r);
            }
        }
        return MicraNone.INSTANCE;
    }

    /** The shared tail of {@code part(...)} and every {@code <part>(...)}: parent, anchor, params[, tags[, label]]. Returns the id. */
    private static String part(PlanApi api, String id, String type, ArgReader r) {
        String parent = r.stringOrNone();
        PlanAnchorArgs anchor = anchor(r.next());
        Map<String, Object> params = map(r.next());
        List<String> tags = r.hasMore() ? strings(r.next()) : List.of();
        api.part(id, type, parent, anchor, params, tags, r.optionalString());
        return id;
    }

    private static void arity(String name, List<Object> args, Arity arity, int line) {
        if (args.size() < arity.min() || args.size() > arity.max()) {
            throw new MicraLangException(line, name + "() takes " + arity.describe() + " arguments but got " + args.size());
        }
    }

    /** Reads call arguments in order and names the 1-based position in type errors. */
    private static final class ArgReader {
        private final List<Object> args;
        private int next;

        ArgReader(List<Object> args) {
            this.args = args;
        }

        boolean hasMore() {
            return next < args.size();
        }

        Object next() {
            return args.get(next++);
        }

        /** The next argument, or None when the call stopped before it. */
        Object optional() {
            return hasMore() ? next() : MicraNone.INSTANCE;
        }

        String string() {
            int position = next + 1;
            if (next() instanceof String s) {
                return s;
            }
            throw new IllegalArgumentException(position + "番目の引数は文字列が必要です");
        }

        String stringOrNone() {
            if (args.get(next) instanceof MicraNone) {
                next++;
                return null;
            }
            return string();
        }

        String optionalString() {
            return hasMore() ? string() : ABSENT_TEXT;
        }
    }

    static int integer(Object v) {
        if (v instanceof Double d && d == Math.rint(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
            return (int) (double) d;
        }
        throw new IllegalArgumentException("整数が必要です(" + PlanValueText.describe(v) + ")");
    }

    private static boolean bool(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        throw new IllegalArgumentException("True か False が必要です(" + PlanValueText.describe(v) + ")");
    }

    private static int[] ints(Object v, int size) {
        List<Object> list = list(v);
        if (list.size() != size) {
            throw new IllegalArgumentException("整数が" + size + "個並んだリストが必要です");
        }
        int[] out = new int[size];
        for (int i = 0; i < size; i++) {
            out[i] = integer(list.get(i));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object v) {
        if (v instanceof List<?> l) {
            return (List<Object>) l;
        }
        throw new IllegalArgumentException("リストが必要です");
    }

    private static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        for (Object o : list(v)) {
            if (!(o instanceof String s)) {
                throw new IllegalArgumentException("文字列のリストが必要です");
            }
            out.add(s);
        }
        return out;
    }

    /** A dict with string keys; None means no entries. */
    private static Map<String, Object> map(Object v) {
        if (v instanceof MicraNone) {
            return new LinkedHashMap<>();
        }
        if (!(v instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("辞書({\"名前\": 値})が必要です");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!(e.getKey() instanceof String key)) {
                throw new IllegalArgumentException("辞書のキーは文字列にしてください");
            }
            out.put(key, e.getValue());
        }
        return out;
    }

    private static String facing(String f) {
        if (!FACINGS.contains(f)) {
            throw new IllegalArgumentException("向きは " + FACINGS_QUOTED + " のどれかです(" + PlanValueText.describe(f) + ")");
        }
        return f;
    }

    /** {@code "node.port"}: both halves non-empty. */
    private static String nodePort(String s) {
        int dot = s.indexOf(NODE_PORT_SEPARATOR);
        if (dot <= 0 || dot == s.length() - 1) {
            throw new IllegalArgumentException("\"ノードID.ポート名\" の形にしてください(" + PlanValueText.describe(s) + ")");
        }
        return s;
    }

    private static int turns(Object v) {
        int n = integer(v);
        if (n < 0 || n > MAX_TURNS) {
            throw new IllegalArgumentException("回転数は0〜" + MAX_TURNS + "です(" + n + ")");
        }
        return n;
    }

    /**
     * {@code [u, v, w]}, {@code [u, v, w, turns, mirror]}, {@code ["surface", target, "outer"|"inner", u, v]} or
     * {@code ["slot", slot, turns, mirror]}; list positions below follow those literal forms.
     */
    private static PlanAnchorArgs anchor(Object v) {
        List<Object> a = list(v);
        if (!a.isEmpty() && a.get(0) instanceof String kind) {
            switch (kind) {
                case ANCHOR_SURFACE -> {
                    if (a.size() != SURFACE_ANCHOR_SIZE || !(a.get(1) instanceof String target) || !(a.get(2) instanceof String side)) {
                        throw new IllegalArgumentException("[\"" + ANCHOR_SURFACE + "\", 壁ID, \"" + SIDE_OUTER + "\"か\"" + SIDE_INNER
                                + "\", u, v] の形にしてください");
                    }
                    if (!side.equals(SIDE_OUTER) && !side.equals(SIDE_INNER)) {
                        throw new IllegalArgumentException("面の側は \"" + SIDE_OUTER + "\" か \"" + SIDE_INNER
                                + "\" です(" + PlanValueText.describe(side) + ")");
                    }
                    return PlanAnchorArgs.surface(target, side, integer(a.get(3)), integer(a.get(4)));
                }
                case ANCHOR_SLOT -> {
                    if (a.size() < SLOT_ANCHOR_MIN_SIZE || a.size() > SLOT_ANCHOR_MAX_SIZE || !(a.get(1) instanceof String slot)) {
                        throw new IllegalArgumentException("[\"" + ANCHOR_SLOT + "\", スロットID, 回転数, 鏡像] の形にしてください");
                    }
                    return PlanAnchorArgs.slot(slot, a.size() > 2 ? turns(a.get(2)) : 0, a.size() > 3 && bool(a.get(3)));
                }
                default -> throw new IllegalArgumentException("位置指定の種類が不明です: " + PlanValueText.describe(kind));
            }
        }
        if (a.size() < ABSOLUTE_ANCHOR_MIN_SIZE || a.size() > ABSOLUTE_ANCHOR_MAX_SIZE) {
            throw new IllegalArgumentException("[u, v, w] か [u, v, w, 回転数, 鏡像] の形にしてください");
        }
        return PlanAnchorArgs.absolute(integer(a.get(0)), integer(a.get(1)), integer(a.get(2)),
                a.size() > 3 ? turns(a.get(3)) : 0, a.size() > 4 && bool(a.get(4)));
    }
}
