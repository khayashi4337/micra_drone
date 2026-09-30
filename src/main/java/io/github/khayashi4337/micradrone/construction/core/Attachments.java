package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.gen.BlockForms;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.Comparator;
import java.util.List;

/**
 * Which blocks hang on a neighbour and fall off, dropping their item, when it goes (a door's other half, a wall sign on
 * its wall). Removals take them first, and a door's two halves are one piece (removed back to back, settled once).
 */
public final class Attachments {
    static final String POTTED_PREFIX = "minecraft:potted_";
    /** Id endings of blocks that stand on or hang from a neighbour (vanilla survives-on / attached blocks). */
    private static final List<String> DEPENDENT_SUFFIXES = List.of(BlockForms.DOOR_SUFFIX, "_trapdoor", BlockForms.SIGN_SUFFIX,
            "_torch", "lantern", "ladder", "_button", "lever", "_carpet", "_pressure_plate", "_banner", "flower_pot", "rail");
    /** Dependents first; a door is placed by its lower half; then top-down, z, x; the upper half before the lower. */
    public static final Comparator<RestoreItem> REMOVAL_ORDER = Comparator
            .<RestoreItem>comparingInt(r -> dependent(r.expectedNow()) ? 0 : 1)
            .thenComparingInt(r -> -anchor(r).y())
            .thenComparingInt(r -> anchor(r).z())
            .thenComparingInt(r -> anchor(r).x())
            .thenComparingInt(r -> -r.pos().y());

    private Attachments() {
    }

    public static boolean dependent(BlockSpec b) {
        String id = b.blockId();
        if (id.startsWith(POTTED_PREFIX)) {
            return true;
        }
        for (String suffix : DEPENDENT_SUFFIXES) {
            if (id.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    public static boolean samePiece(RestoreItem a, RestoreItem b) {
        String id = a.expectedNow().blockId();
        return id.endsWith(BlockForms.DOOR_SUFFIX) && id.equals(b.expectedNow().blockId()) && a.pos().x() == b.pos().x()
                && a.pos().z() == b.pos().z() && Math.abs(a.pos().y() - b.pos().y()) == 1;
    }

    /** A door's upper half sorts with its lower half (one below), so the two stay next to each other. */
    private static IntPos anchor(RestoreItem r) {
        boolean upperDoor = r.expectedNow().blockId().endsWith(BlockForms.DOOR_SUFFIX)
                && BlockForms.HALF_UPPER.equals(r.expectedNow().get(BlockForms.PROP_HALF));
        return upperDoor ? r.pos().plus(0, -1, 0) : r.pos();
    }
}
