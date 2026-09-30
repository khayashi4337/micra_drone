package io.github.khayashi4337.micradrone.construction.core;

/**
 * What placing a block destroys. Fluids, leaves and an empty container need the user's explicit destructive
 * confirmation (F-5); terrain has its own terraform confirmation (E-TERRAFORM-UNCONFIRMED).
 */
public enum Destruction {
    NONE, FLUID, LEAVES, EMPTY_CONTAINER, TERRAIN;

    public boolean needsDestructiveConfirm() {
        return this == FLUID || this == LEAVES || this == EMPTY_CONTAINER;
    }
}
