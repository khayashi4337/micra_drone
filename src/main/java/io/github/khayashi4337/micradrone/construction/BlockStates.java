package io.github.khayashi4337.micradrone.construction;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.TreeMap;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** BlockSpec (plain strings) to BlockState and back. The spec's text form is exactly the command syntax id[k=v,...]. */
public final class BlockStates {
    private BlockStates() {
    }

    public static BlockState toState(BlockSpec spec) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), spec.toString(), false).blockState();
        } catch (CommandSyntaxException e) {
            throw new IllegalArgumentException("not a block state of this game: " + spec + " (" + e.getMessage() + ")", e);
        }
    }

    public static BlockSpec toSpec(BlockState state) {
        TreeMap<String, String> props = new TreeMap<>();
        for (Property<?> p : state.getProperties()) {
            props.put(p.getName(), valueName(state, p));
        }
        return new BlockSpec(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), props);
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> p) {
        return p.getName(state.getValue(p));
    }
}
