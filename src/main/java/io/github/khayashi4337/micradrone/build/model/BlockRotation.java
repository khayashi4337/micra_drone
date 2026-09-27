package io.github.khayashi4337.micradrone.build.model;

import java.util.Map;
import java.util.TreeMap;

/**
 * Rotates and mirrors block-state properties along with the positions they belong to, so a plan compiled
 * in the local frame stays consistent once mapped to the world. Local semantics: {@code axis} x runs along u
 * and z along w; {@code facing} values use {@link Facing}'s local meaning.
 */
public final class BlockRotation {
    // Block-state property names. The direction names are Facing's wire form, i.e. Facing.NORTH.lower() etc.
    private static final String P_FACING = "facing";
    private static final String P_AXIS = "axis";
    private static final String P_ROTATION = "rotation";
    private static final String P_NORTH = "north";
    private static final String P_EAST = "east";
    private static final String P_SOUTH = "south";
    private static final String P_WEST = "west";
    private static final String P_HINGE = "hinge";
    private static final String P_SHAPE = "shape";
    private static final String LEFT_SUFFIX = "_left";
    private static final String RIGHT_SUFFIX = "_right";
    private static final String LEFT = "left";
    private static final String RIGHT = "right";

    private static final int ROTATION_STEPS_PER_QUARTER_TURN = 4; // signs use 16 rotation steps per full turn
    private static final int ROTATION_STEPS = 16;

    private BlockRotation() {
    }

    public static BlockSpec rotate(BlockSpec spec, int quarterTurns) {
        int q = Math.floorMod(quarterTurns, Rot.QUARTER_TURNS_PER_CIRCLE);
        if (q == 0 || spec.properties().isEmpty()) {
            return spec;
        }
        TreeMap<String, String> out = new TreeMap<>();
        for (Map.Entry<String, String> e : spec.properties().entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            switch (key) {
                case P_FACING -> out.put(key, rotateDirection(value, q));
                case P_AXIS -> out.put(key, q % Rot.HALF_TURN == 1 ? swapXZ(value) : value);
                case P_ROTATION -> out.put(key, String.valueOf(
                        Math.floorMod(Integer.parseInt(value) + ROTATION_STEPS_PER_QUARTER_TURN * q, ROTATION_STEPS)));
                case P_NORTH, P_EAST, P_SOUTH, P_WEST -> {
                    // handled below so that keys move together
                }
                default -> out.put(key, value);
            }
        }
        for (Facing f : Facing.values()) {
            String value = spec.properties().get(f.lower());
            if (value != null) {
                out.put(f.rotate(q).lower(), value);
            }
        }
        return new BlockSpec(spec.blockId(), out);
    }

    /** Mirrors u to -u: EAST and WEST swap; door hinges and stair corner shapes swap left and right. */
    public static BlockSpec mirrorU(BlockSpec spec) {
        if (spec.properties().isEmpty()) {
            return spec;
        }
        TreeMap<String, String> out = new TreeMap<>();
        for (Map.Entry<String, String> e : spec.properties().entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            switch (key) {
                case P_FACING -> out.put(key, mirrorDirection(value));
                case P_HINGE -> out.put(key, swapLeftRight(value));
                case P_SHAPE -> out.put(key, swapLeftRight(value));
                case P_EAST, P_WEST -> {
                    // handled below
                }
                case P_ROTATION -> out.put(key, String.valueOf(Math.floorMod(-Integer.parseInt(value), ROTATION_STEPS)));
                default -> out.put(key, value);
            }
        }
        String east = spec.properties().get(P_EAST);
        String west = spec.properties().get(P_WEST);
        if (east != null) {
            out.put(P_WEST, east);
        }
        if (west != null) {
            out.put(P_EAST, west);
        }
        return new BlockSpec(spec.blockId(), out);
    }

    /** Applies {@link Rot}: mirror first, then the quarter turns. */
    public static BlockSpec transform(BlockSpec spec, Rot rot) {
        BlockSpec mirrored = rot.mirror() ? mirrorU(spec) : spec;
        return rotate(mirrored, rot.quarterTurns());
    }

    private static String rotateDirection(String value, int q) {
        return switch (value) {
            case P_NORTH, P_EAST, P_SOUTH, P_WEST -> Facing.parse(value).rotate(q).lower();
            default -> value; // up / down
        };
    }

    private static String mirrorDirection(String value) {
        return switch (value) {
            case P_EAST -> P_WEST;
            case P_WEST -> P_EAST;
            default -> value;
        };
    }

    private static String swapXZ(String axis) {
        return switch (axis) {
            case "x" -> "z";
            case "z" -> "x";
            default -> axis;
        };
    }

    private static String swapLeftRight(String value) {
        if (value.endsWith(LEFT_SUFFIX)) {
            return value.substring(0, value.length() - LEFT_SUFFIX.length()) + RIGHT_SUFFIX;
        }
        if (value.endsWith(RIGHT_SUFFIX)) {
            return value.substring(0, value.length() - RIGHT_SUFFIX.length()) + LEFT_SUFFIX;
        }
        return switch (value) {
            case LEFT -> RIGHT;
            case RIGHT -> LEFT;
            default -> value;
        };
    }
}
