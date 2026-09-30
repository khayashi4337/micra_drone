package io.github.khayashi4337.micradrone.construction.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A translation key plus its already-stringified arguments, ready for {@code Component.translatable} at the
 * adapter edge. Arguments are converted with {@link String#valueOf} here so a child's line can only carry
 * text, never a machine-readable object.
 */
public record MessageKey(String key, List<String> args) {
    public MessageKey {
        Objects.requireNonNull(key);
        args = List.copyOf(args);
    }

    public static MessageKey of(String key, Object... args) {
        List<String> text = new ArrayList<>(args.length);
        for (Object arg : args) {
            text.add(String.valueOf(arg));
        }
        return new MessageKey(key, text);
    }
}
