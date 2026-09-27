package io.github.khayashi4337.micradrone.build.model;

public enum ConnKind {
    ROTATION, ITEM, FLUID, REDSTONE, HEAT, DOCK;

    public String lower() {
        return WireEnum.lower(this);
    }

    public static ConnKind parse(String text) {
        return WireEnum.parse(ConnKind.class, text);
    }
}
