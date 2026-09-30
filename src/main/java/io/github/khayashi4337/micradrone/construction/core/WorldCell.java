package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.ObservedBlock;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** One position as the construction sees it. An unloaded position has no block: it is unknown, never "missing" (N-29). */
public record WorldCell(boolean loaded, ObservedBlock observed, Set<CellTrait> traits) {
    private static final WorldCell UNLOADED = new WorldCell(false, null, Set.of());

    public WorldCell {
        if (loaded) {
            Objects.requireNonNull(observed, "observed");
        } else if (observed != null) {
            throw new IllegalArgumentException("an unloaded position has no observed block");
        }
        traits = traits.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(traits));
    }

    public static WorldCell unloaded() {
        return UNLOADED;
    }

    public static WorldCell of(BlockSpec block, CellTrait... traits) {
        return new WorldCell(true, new ObservedBlock(block), traitSet(traits));
    }

    public static WorldCell withBlockEntity(BlockSpec block, String type, CellTrait... traits) {
        return new WorldCell(true, new ObservedBlock(block, true, type), traitSet(traits));
    }

    private static Set<CellTrait> traitSet(CellTrait... traits) {
        return traits.length == 0 ? Set.of() : EnumSet.of(traits[0], traits);
    }

    public BlockSpec block() {
        if (!loaded) {
            throw new IllegalStateException("an unloaded position has no block");
        }
        return observed.block();
    }
}
