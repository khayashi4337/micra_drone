package io.github.khayashi4337.micradrone.construction.core;

/** Whether an item id exists in the running game (the adapter reads the item registry). */
@FunctionalInterface
public interface ItemCatalog {
    ItemCatalog ANY = itemId -> true;

    boolean exists(String itemId);
}
