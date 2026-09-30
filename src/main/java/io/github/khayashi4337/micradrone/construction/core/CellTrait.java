package io.github.khayashi4337.micradrone.construction.core;

/**
 * What the adapter observed about a position, beyond the block: vanilla's "can be replaced" (plants, snow layers, air),
 * a fluid, leaves, the micradrone:terraformable tag, unbreakable (destroy speed below zero), and an empty container.
 */
public enum CellTrait {
    REPLACEABLE, FLUID, LEAVES, TERRAFORMABLE, UNBREAKABLE, EMPTY_CONTAINER
}
