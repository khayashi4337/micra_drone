package io.github.khayashi4337.micradrone.build.model;

import java.util.Map;
import java.util.TreeMap;

/**
 * Rotates and mirrors block-state properties along with the positions they belong to, so a plan compiled
 * in the local frame stays consistent once mapped to the world. Local semantics: {@code axis} x runs along u
 * and z along w; {@code facing} values use {@link Facing}'s local meaning.
 */
public final class BlockRotation {
    private static final int ROTATION_STEPS_PER_QUARTER_TURN = 4; // signs use 16 rotation steps per full turn
    private static final int ROTATION_STEPS = 16;

    private BlockRotation() {
    }

    public static BlockSpec rotate(BlockSpec spec, int quarterTurns) {
        int q = Math.floorMod(quarterTurns, 4);
        if (q == 0 || spec.properties().isEmpty()) {
            return spec;
        }
        TreeMap<String, String> out = new TreeMap<>();
        for (Map.Entry<String, String> e : spec.properties().entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            switch (key) {
                case "facing" -> out.put(key, rotateDirection(value, q));
                case "axis" -> out.put(key, q % 2 == 1 ? swapXZ(value) : value);
                case "rotation" -> out.put(key, String.valueOf(
                        Math.floorMod(Integer.parseInt(value) + ROTATION_STEPS_PER_QUARTER_TURN * q, ROTATION_STEPS)));
                case "north", "east", "south", "west" -> {
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
                case "facing" -> out.put(key, mirrorDirection(value));
                case "hinge" -> out.put(key, swapLeftRight(value));
                case "shape" -> out.put(key, swapLeftRight(value));
                case "east", "west" -> {
                    // handled below
                }
                case "rotation" -> out.put(key, String.valueOf(Math.floorMod(-Integer.parseInt(value), ROTATION_STEPS)));
                default -> out.put(key, value);
            }
        }
        String east = spec.properties().get("east");
        String west = spec.properties().get("west");
        if (east != null) {
            out.put("west", east);
        }
        if (west != null) {
            out.put("east", west);
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
            case "north", "east", "south", "west" -> Facing.parse(value).rotate(q).lower();
            default -> value; // up / down
        };
    }

    private static String mirrorDirection(String value) {
        return switch (value) {
            case "east" -> "west";
            case "west" -> "east";
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
        if (value.endsWith("_left")) {
            return value.substring(0, value.length() - "_left".length()) + "_right";
        }
        if (value.endsWith("_right")) {
            return value.substring(0, value.length() - "_right".length()) + "_left";
        }
        return switch (value) {
            case "left" -> "right";
            case "right" -> "left";
            default -> value;
        };
    }
}
