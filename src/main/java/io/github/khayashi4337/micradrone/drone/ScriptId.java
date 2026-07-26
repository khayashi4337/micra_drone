package io.github.khayashi4337.micradrone.drone;

/**
 * Script identifiers as they travel between the GUI and the server (issue #6, chest library).
 * Three shapes exist:
 * <ul>
 *   <li>File scripts: the plain {@code *.mdrone} file name, validated by
 *       {@link ScriptFileStore#isValidScriptName}.</li>
 *   <li>Chest scrolls: {@code scroll:<chestIndex>:<slot>}, pointing into the controller's library
 *       chests (see {@code ScriptChestLibrary}). Indexes are re-resolved against the chests at use
 *       time, so a stale id (items moved) fails loudly instead of hitting the wrong scroll.</li>
 *   <li>Inventory scrolls: {@code inv:<slot>}, pointing into the REQUESTING PLAYER's own inventory
 *       (林さんの要望: 参照する巻物の置き場所にインベントリも含める). Slot is re-resolved against that
 *       specific player's inventory at use time, same "stale id fails loudly" idiom as chest
 *       scrolls - and since it's always resolved against whichever player is asking, never another
 *       player's inventory, this id shape is meaningless without a player in hand.</li>
 * </ul>
 * (A fourth shape, the id of a scroll slotted directly into the controller block, existed for issue
 * #7's jukebox-style slot; the GUI-reduction follow-up replaced it with plain list selection - see
 * {@code DroneControllerBlockEntity#selectedScript} - so that shape no longer exists.)
 * Minecraft-free so the parsing/validation rules are unit-testable.
 */
public final class ScriptId {
    private static final String SCROLL_PREFIX = "scroll:";
    private static final String INVENTORY_PREFIX = "inv:";

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

    public static String inventoryScrollId(int slot) {
        if (slot < 0) {
            throw new IllegalArgumentException("slot must be >= 0: " + slot);
        }
        return INVENTORY_PREFIX + slot;
    }

    public static boolean isInventoryScrollId(String id) {
        return inventorySlot(id) >= 0;
    }

    /** The inventory slot of an inventory scroll id, or -1 if {@code id} isn't a well-formed one. */
    public static int inventorySlot(String id) {
        if (id == null || !id.startsWith(INVENTORY_PREFIX)) {
            return -1;
        }
        return parseNonNegativeInt(id.substring(INVENTORY_PREFIX.length()));
    }

    /** True for every id shape the server accepts from the network: a valid file name, a scroll id, or an inventory scroll id. */
    public static boolean isValidId(String id) {
        return ScriptFileStore.isValidScriptName(id) || isScrollId(id) || isInventoryScrollId(id);
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
