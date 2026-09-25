package io.github.khayashi4337.micradrone.build.model;

import java.util.Locale;

public enum ConnKind {
    ROTATION, ITEM, FLUID, REDSTONE, HEAT, DOCK;

    public String lower() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ConnKind parse(String text) {
        return valueOf(text.trim().toUpperCase(Locale.ROOT));
    }
}
