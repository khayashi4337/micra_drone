package io.github.khayashi4337.micradrone.build.model;

import java.util.Locale;

/**
 * The wire-name convention shared by the enum types here: the lowercase enum name on the wire and a
 * case-insensitive, whitespace-tolerant read back.
 */
final class WireEnum {
    private WireEnum() {
    }

    static String lower(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    static <E extends Enum<E>> E parse(Class<E> type, String text) {
        return Enum.valueOf(type, text.trim().toUpperCase(Locale.ROOT));
    }
}
