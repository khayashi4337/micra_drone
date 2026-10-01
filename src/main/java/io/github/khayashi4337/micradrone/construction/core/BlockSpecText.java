package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The text form of a {@link BlockSpec} ({@code "minecraft:oak_stairs[facing=north,half=bottom]"}), the inverse of
 * {@link BlockSpec#toString()}: the saved trees (journal records, the manifest palette, the placed registry) write a
 * block as this one string. A form {@link BlockSpec#toString()} never produces is refused, never guessed.
 */
public final class BlockSpecText {
    /** A block id's wire form ({@code namespace:path}, lowercase); a saved file's id is checked, not trusted (F-22). */
    private static final Pattern BLOCK_ID = Pattern.compile("[a-z0-9_.\\-]+:[a-z0-9_./\\-]+");
    private static final char PROPS_OPEN = '[';
    private static final char PROPS_CLOSE = ']';
    private static final String ENTRY_SEPARATOR = ",";
    private static final String KEY_VALUE_SEPARATOR = "=";
    private static final int KEY_VALUE_PARTS = 2;

    private BlockSpecText() {
    }

    public static BlockSpec parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("a block spec is null");
        }
        int open = text.indexOf(PROPS_OPEN);
        String id = open < 0 ? text : text.substring(0, open);
        if (!BLOCK_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("not a block id: " + id);
        }
        if (open < 0) {
            return new BlockSpec(id, new TreeMap<>());
        }
        if (text.length() < open + 2 || text.charAt(text.length() - 1) != PROPS_CLOSE) {
            throw new IllegalArgumentException("block properties end with ']': " + text);
        }
        String inner = text.substring(open + 1, text.length() - 1);
        if (inner.isEmpty()) {
            // toString writes no brackets for a property-less block, so "id[]" is not its output
            throw new IllegalArgumentException("a block without properties writes no brackets: " + text);
        }
        TreeMap<String, String> props = new TreeMap<>();
        for (String entry : inner.split(ENTRY_SEPARATOR, -1)) {
            String[] kv = entry.split(KEY_VALUE_SEPARATOR, -1);
            if (kv.length != KEY_VALUE_PARTS || kv[0].isEmpty() || kv[1].isEmpty()) {
                throw new IllegalArgumentException("a block property is 'key=value', not " + entry);
            }
            props.put(kv[0], kv[1]);
        }
        return new BlockSpec(id, props);
    }
}
