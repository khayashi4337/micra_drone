package io.github.khayashi4337.micradrone.build.model;

import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/** A block id plus block-state properties, as plain strings (no Minecraft types). Properties are kept sorted. */
public record BlockSpec(String blockId, SortedMap<String, String> properties) {
    public static final BlockSpec AIR = new BlockSpec("minecraft:air", new TreeMap<>());

    public BlockSpec {
        Objects.requireNonNull(blockId, "blockId");
        properties = SortedCopies.map(properties);
    }

    /** True for {@link #AIR}'s block id. */
    public boolean isAir() {
        return AIR.blockId().equals(blockId);
    }

    /** {@code of("minecraft:oak_stairs", "facing", "north", "half", "bottom")}. */
    public static BlockSpec of(String blockId, String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("properties must come as key/value pairs");
        }
        TreeMap<String, String> props = new TreeMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            props.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return new BlockSpec(blockId, props);
    }

    public BlockSpec with(String key, String value) {
        TreeMap<String, String> copy = new TreeMap<>(properties);
        copy.put(key, value);
        return new BlockSpec(blockId, copy);
    }

    public String get(String key) {
        return properties.get(key);
    }

    @Override
    public String toString() {
        if (properties.isEmpty()) {
            return blockId;
        }
        StringBuilder sb = new StringBuilder(blockId).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : properties.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.append(']').toString();
    }
}
