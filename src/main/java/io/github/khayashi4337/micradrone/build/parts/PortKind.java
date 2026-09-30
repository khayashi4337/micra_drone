package io.github.khayashi4337.micradrone.build.parts;

/** What flows through a port: rotation, items, fluid, a redstone signal, heat, or a dock for a moving structure. */
public enum PortKind {
    ROTATION_IN, ROTATION_OUT, ITEM_IN, ITEM_OUT, FLUID_IN, FLUID_OUT, REDSTONE, HEAT, DOCK
}
