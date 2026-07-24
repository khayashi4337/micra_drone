package io.github.khayashi4337.micradrone.drone;

/**
 * Script identifiers as they travel between the GUI and the server (issue #6, chest library;
 * issue #7, controller slot). Three shapes exist:
 * <ul>
 *   <li>File scripts: the plain {@code *.mdrone} file name, validated by
 *       {@link ScriptFileStore#isValidScriptName}.</li>
 *   <li>Chest scrolls: {@code scroll:<chestIndex>:<slot>}, pointing into the controller's library
 *       chests (see {@code ScriptChestLibrary}). Indexes are re-resolved against the chests at use
 *       time, so a stale id (items moved) fails loudly instead of hitting the wrong scroll.</li>
 *   <li>{@link #CONTROLLER_ID}: the single scroll slotted into the controller block itself
 *       (jukebox-style, issue #7) - the script that a redstone signal runs.</li>
 * </ul>
 * Minecraft-free so the parsing/validation rules are unit-testable.
 */
public final class ScriptId {
    /** The id of the scroll slotted into the controller block itself. */
    public static final String CONTROLLER_ID = "controller";

    private static final String SCROLL_PREFIX = "scroll:";

    private ScriptId() {
    }

    public static String scrollId(int chestIndex, int slot) {
        if (chestIndex < 0 || slot < 0) {
            throw new IllegalArgumentException("chestIndex/slot must be >= 0: " + chestIndex + ":" + slot);
        }
        return SCROLL_PREFIX + chestIndex + ":" + slot;
    }

    public static boolean isScrollId(String id) {
        return parse(id) != null;
    }

    /** The chest index of a scroll id, or -1 if {@code id} isn't a well-formed scroll id. */
    public static int scrollChestIndex(String id) {
        int[] parsed = parse(id);
        return parsed == null ? -1 : parsed[0];
    }

    /** The slot of a scroll id, or -1 if {@code id} isn't a well-formed scroll id. */
    public static int scrollSlot(String id) {
        int[] parsed = parse(id);
        return parsed == null ? -1 : parsed[1];
    }

    /** True for every id shape the server accepts from the network: a valid file name, a scroll id, or the controller slot. */
    public static boolean isValidId(String id) {
        return CONTROLLER_ID.equals(id) || ScriptFileStore.isValidScriptName(id) || isScrollId(id);
    }

    private static int[] parse(String id) {
        if (id == null || !id.startsWith(SCROLL_PREFIX)) {
            return null;
        }
        String[] parts = id.split(":", -1);
        if (parts.length != 3) {
            return null;
        }
        int chestIndex = parseNonNegativeInt(parts[1]);
        int slot = parseNonNegativeInt(parts[2]);
        return chestIndex < 0 || slot < 0 ? null : new int[]{chestIndex, slot};
    }

    /** Digits only (no signs, no whitespace), bounded well below overflow; -1 when malformed. */
    private static int parseNonNegativeInt(String text) {
        if (text.isEmpty() || text.length() > 6) {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
        }
        return value;
    }
}
