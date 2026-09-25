package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.List;

/**
 * Block states for the shapes generators need. Only the states that matter are listed (the rest are left to the game:
 * stair corner shapes, fence connections, waterlogging), and verification compares only what is listed.
 */
public final class BlockForms {
    // Vanilla block-state property names and values, shared with the item count (BomCalculator).
    public static final String PROP_FACING = "facing";
    public static final String PROP_HALF = "half";
    public static final String PROP_TYPE = "type";
    public static final String PROP_HINGE = "hinge";
    public static final String PROP_HANGING = "hanging";
    public static final String PROP_AXIS = "axis";
    public static final String HALF_UPPER = "upper";
    public static final String HALF_LOWER = "lower";
    public static final String HALF_TOP = "top";
    public static final String HALF_BOTTOM = "bottom";
    public static final String TYPE_DOUBLE = "double";
    private static final String HINGE_LEFT = "left";
    private static final String HINGE_RIGHT = "right";

    // Id suffixes that name a block's shape in vanilla.
    public static final String DOOR_SUFFIX = "_door";
    public static final String STAIRS_SUFFIX = "_stairs";
    public static final String SLAB_SUFFIX = "_slab";
    public static final String WALL_SIGN_SUFFIX = "_wall_sign";
    public static final String SIGN_SUFFIX = "_sign";
    private static final String TRAPDOOR_SUFFIX = "_trapdoor";
    private static final String PANE_SUFFIX = "_pane";
    private static final String FENCE_SUFFIX = "_fence";

    private static final String LADDER = "minecraft:ladder";
    private static final String LANTERN = "minecraft:lantern";
    private static final String IRON_BARS = "minecraft:iron_bars";

    private static final List<String> AXIS_SUFFIXES = List.of("_log", "_wood", "_stem", "_hyphae", "_block_axis");
    private static final List<String> AXIS_IDS = List.of("minecraft:bamboo_block", "minecraft:stripped_bamboo_block",
            "minecraft:hay_block", "minecraft:bone_block", "minecraft:basalt", "minecraft:polished_basalt");

    private BlockForms() {
    }

    public static BlockSpec plain(String id) {
        return BlockSpec.of(id);
    }

    private static String half(boolean top) {
        return top ? HALF_TOP : HALF_BOTTOM;
    }

    /** {@code back} is the side of the tall back of the stair (the vanilla "facing"). */
    public static BlockSpec stairs(String id, Facing back, boolean top) {
        return BlockSpec.of(id, PROP_FACING, back.lower(), PROP_HALF, half(top));
    }

    public static BlockSpec slab(String id, boolean top) {
        return BlockSpec.of(id, PROP_TYPE, half(top));
    }

    public static BlockSpec door(String id, Facing facing, boolean upper, boolean hingeRight) {
        return BlockSpec.of(id, PROP_FACING, facing.lower(), PROP_HALF, upper ? HALF_UPPER : HALF_LOWER,
                PROP_HINGE, hingeRight ? HINGE_RIGHT : HINGE_LEFT);
    }

    /** A block whose only listed state is the direction it faces (fence gates, wall signs, ladders). */
    private static BlockSpec facing(String id, Facing facing) {
        return BlockSpec.of(id, PROP_FACING, facing.lower());
    }

    public static BlockSpec gate(String id, Facing facing) {
        return facing(id, facing);
    }

    public static BlockSpec ladder(Facing facing) {
        return facing(LADDER, facing);
    }

    public static BlockSpec wallSign(String id, Facing facing) {
        return facing(id, facing);
    }

    public static BlockSpec lantern(boolean hanging) {
        return BlockSpec.of(LANTERN, PROP_HANGING, String.valueOf(hanging));
    }

    public static boolean isAxisBlock(String id) {
        if (AXIS_IDS.contains(id)) {
            return true;
        }
        for (String suffix : AXIS_SUFFIXES) {
            if (id.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** {@code axis} is x (along u), y (up) or z (along w) in the local frame. */
    public static BlockSpec axisBlock(String id, String axis) {
        return BlockSpec.of(id, PROP_AXIS, axis);
    }

    /** A floor tile: a trapdoor lies closed on the bottom half, a slab is a bottom slab, anything else is a plain block. */
    public static BlockSpec flat(String id) {
        if (id.endsWith(TRAPDOOR_SUFFIX)) {
            return BlockSpec.of(id, PROP_HALF, HALF_BOTTOM);
        }
        if (id.endsWith(SLAB_SUFFIX)) {
            return slab(id, false);
        }
        return plain(id);
    }

    /** Blocks whose state depends on their neighbours are checked by id only; stateless blocks exactly. */
    public static VerifyMode verifyFor(BlockSpec spec) {
        String id = spec.blockId();
        if (id.endsWith(PANE_SUFFIX) || id.endsWith(FENCE_SUFFIX) || id.equals(IRON_BARS)) {
            return VerifyMode.BLOCK_ONLY;
        }
        return spec.properties().isEmpty() ? VerifyMode.EXACT : VerifyMode.STATE_SUBSET;
    }
}
