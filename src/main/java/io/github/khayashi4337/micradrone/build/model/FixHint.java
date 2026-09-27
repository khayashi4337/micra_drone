package io.github.khayashi4337.micradrone.build.model;

import java.util.Map;

/** A machine-readable suggestion attached to an {@link Issue}, e.g. {@code ADD_POWER_SOURCE{need_su=512}}. */
public record FixHint(String kind, Map<String, String> args) {
    public FixHint {
        args = SortedCopies.map(args);
    }
}
