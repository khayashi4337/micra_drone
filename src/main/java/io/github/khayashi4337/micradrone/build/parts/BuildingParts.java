package io.github.khayashi4337.micradrone.build.parts;

import io.github.khayashi4337.micradrone.build.model.Facing;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The building parts (micra:*): what a plan may build from vanilla blocks, and the default palette that turns a
 * role such as "wall" into a block. Parameters, ranges, defaults and phases follow design doc 05, section 1.1.1;
 * BuildingPartsTest pins them to that table.
 */
public final class BuildingParts {
    public static final String ID_PREFIX = "micra:";

    private static final String DISPLAY_NAME_PREFIX = "micradrone.part.";
    private static final String BLOCK_NAMESPACE = "minecraft:";

    // Palette roles. A material parameter names one of these (or a block id) and the palette maps it to a block.
    private static final String ROLE_WALL = "wall";
    private static final String ROLE_FLOOR = "floor";
    private static final String ROLE_ROOF = "roof";
    private static final String ROLE_FOUNDATION = "foundation";
    private static final String ROLE_PILLAR = "pillar";
    private static final String ROLE_BEAM = "beam";
    private static final String ROLE_TRIM = "trim";
    private static final String ROLE_GLASS = "glass";
    private static final String ROLE_DOOR = "door";
    private static final String ROLE_GATE = "gate";
    private static final String ROLE_FENCE = "fence";
    private static final String ROLE_STAIRS = "stairs";
    private static final String ROLE_RAMP = "ramp";
    private static final String ROLE_CATWALK = "catwalk";
    private static final String ROLE_CHIMNEY = "chimney";
    private static final String ROLE_PATH = "path";
    private static final String ROLE_PAD = "pad";
    private static final String ROLE_MARKER = "marker";
    private static final String ROLE_CARGO = "cargo";
    private static final String ROLE_SIGN = "sign";
    private static final String ROLE_PLANTER = "planter";
    private static final String ROLE_PLANT = "plant";

    // Blocks that serve more than one role.
    private static final String STONE_BRICKS = "stone_bricks";
    private static final String OAK_PLANKS = "oak_planks";

    // Parameter names that several parts share.
    private static final String WIDTH = "width";
    private static final String DEPTH = "depth";
    private static final String HEIGHT = "height";
    private static final String LENGTH = "length";
    private static final String LEVEL = "level";
    private static final String KIND = "kind";
    private static final String DIR = "dir";
    private static final String AXIS = "axis";
    private static final String RAIL = "rail";
    private static final String MATERIAL = "material";

    // The three local axes, as the values of an "axis" or "ridge" parameter.
    private static final String AXIS_U = "u";
    private static final String AXIS_V = "v";
    private static final String AXIS_W = "w";

    // Volatile block states of a door: they change while the world runs and must not fail a verification.
    private static final String STATE_OPEN = "open";
    private static final String STATE_POWERED = "powered";

    // Limits that recur across parts with the same meaning.
    /** Longest side of a structure and longest straight run of a flat part, in blocks. */
    private static final int MAX_SPAN = 64;
    /** Largest u or w position inside a structure. */
    private static final int MAX_INDEX = MAX_SPAN - 1;
    private static final int MAX_FLOORS = 8;
    private static final int MAX_LEVEL = MAX_FLOORS - 1;
    /** Longest column, ladder, staircase, ramp or chimney, in blocks. */
    private static final int MAX_RUN = 32;
    /** Widest strip, border or foundation depth, in blocks. */
    private static final int MAX_STRIP = 8;
    /** A sign holds four lines of at most fifteen characters. */
    private static final int MAX_SIGN_TEXT = 60;
    /** A floor hole is a rectangle written as four numbers (u0, w0, u1, w1). */
    private static final int VALUES_PER_HOLE = 4;
    private static final int MAX_HOLES = 16;
    private static final int MAX_HOLE_VALUES = MAX_HOLES * VALUES_PER_HOLE;

    /** The four horizontal directions as parameter values, in {@link Facing} order. */
    private static final String[] DIRECTIONS = Arrays.stream(Facing.values()).map(Facing::lower)
            .toArray(String[]::new);

    /** Role to block id. Part of the registry version hash. */
    public static final Map<String, String> DEFAULT_PALETTE = defaultPalette();

    private static final List<PartType> PARTS = buildParts();

    /** The part names without the {@link #ID_PREFIX}, in dictionary order. */
    public static final List<String> NAMES = PARTS.stream().map(p -> p.id().substring(ID_PREFIX.length())).sorted()
            .toList();

    private static final PartTypeRegistry REGISTRY = buildRegistry();

    private BuildingParts() {
    }

    public static PartTypeRegistry registry() {
        return REGISTRY;
    }

    private static Map<String, String> defaultPalette() {
        Map<String, String> roles = Map.ofEntries(
                role(ROLE_WALL, STONE_BRICKS), role(ROLE_FLOOR, OAK_PLANKS), role(ROLE_ROOF, OAK_PLANKS),
                role(ROLE_FOUNDATION, "cobblestone"), role(ROLE_PILLAR, STONE_BRICKS), role(ROLE_BEAM, "oak_log"),
                role(ROLE_TRIM, STONE_BRICKS), role(ROLE_GLASS, "glass_pane"), role(ROLE_DOOR, "oak_door"),
                role(ROLE_GATE, "oak_fence_gate"), role(ROLE_FENCE, "oak_fence"), role(ROLE_STAIRS, "oak_stairs"),
                role(ROLE_RAMP, "stone"), role(ROLE_CATWALK, "iron_trapdoor"), role(ROLE_CHIMNEY, "bricks"),
                role(ROLE_PATH, "gravel"), role(ROLE_PAD, "smooth_stone"), role(ROLE_MARKER, "yellow_concrete"),
                role(ROLE_CARGO, "barrel"), role(ROLE_SIGN, "oak_wall_sign"), role(ROLE_PLANTER, "dirt"),
                role(ROLE_PLANT, "poppy"));
        // Sorted, so the hashed palette and any listing of it do not depend on Map.ofEntries' iteration order.
        return Collections.unmodifiableMap(new TreeMap<>(roles));
    }

    private static Map.Entry<String, String> role(String role, String block) {
        return Map.entry(role, BLOCK_NAMESPACE + block);
    }

    private static PartTypeRegistry buildRegistry() {
        PartTypeRegistry.Builder registry = PartTypeRegistry.builder().defaultPalette(DEFAULT_PALETTE);
        PARTS.forEach(registry::register);
        return registry.build();
    }

    private static List<PartType> buildParts() {
        return List.of(
                part("structure", PartCategory.STRUCTURE, BuildPhase.STRUCTURE, VerifyMode.EXACT,
                        "A building: a footprint with floors, the parent of its walls, floors and roof.",
                        ParamSpec.integer(WIDTH, 3, MAX_SPAN, 7), ParamSpec.integer(DEPTH, 3, MAX_SPAN, 7),
                        ParamSpec.integer("floors", 1, MAX_FLOORS, 1), ParamSpec.integer("floor_height", 3, 8, 4)),
                part("foundation", PartCategory.STRUCTURE, BuildPhase.STRUCTURE, VerifyMode.EXACT,
                        "A solid base under the building.",
                        ParamSpec.integer("margin", 0, MAX_STRIP, 0), ParamSpec.integer(DEPTH, 1, MAX_STRIP, 1),
                        material(ROLE_FOUNDATION)),
                part("floor", PartCategory.STRUCTURE, BuildPhase.STRUCTURE, VerifyMode.STATE_SUBSET,
                        "A floor of blocks or slabs with optional holes for stairs.",
                        ParamSpec.integer(LEVEL, 0, MAX_LEVEL, 0), blockOrSlab(KIND),
                        ParamSpec.intList("holes", 0, MAX_INDEX, MAX_HOLE_VALUES), material(ROLE_FLOOR)),
                part("wall", PartCategory.STRUCTURE, BuildPhase.ENVELOPE, VerifyMode.EXACT,
                        "A wall along one side of a building.",
                        requiredDirection("side"), ParamSpec.integer(LEVEL, 0, MAX_LEVEL, 0),
                        ParamSpec.integer(HEIGHT, 0, 16, 0), ParamSpec.integer("thickness", 1, 3, 1),
                        ParamSpec.integer("from", 0, MAX_INDEX, 0), ParamSpec.integer(LENGTH, 0, MAX_SPAN, 0),
                        firstIsDefault("part", "full", "half"), material(ROLE_WALL)),
                part("pillar", PartCategory.STRUCTURE, BuildPhase.STRUCTURE, VerifyMode.EXACT,
                        "A column with an optional base and capital.",
                        ParamSpec.integer(HEIGHT, 1, MAX_RUN, 4), ParamSpec.bool("base", true),
                        ParamSpec.bool("capital", true), material(ROLE_PILLAR)),
                part("beam", PartCategory.STRUCTURE, BuildPhase.STRUCTURE, VerifyMode.STATE_SUBSET,
                        "A straight horizontal or vertical beam.",
                        firstIsDefault(AXIS, AXIS_U, AXIS_V, AXIS_W), ParamSpec.integer(LENGTH, 1, MAX_SPAN, 3),
                        material(ROLE_BEAM)),
                part("roof", PartCategory.ROOF, BuildPhase.ENVELOPE, VerifyMode.STATE_SUBSET,
                        "A roof of stairs and slabs: gable, hip, flat, shed, sawtooth or monitor.",
                        firstIsDefault(KIND, "gable", "hip", "flat", "shed", "sawtooth", "monitor"),
                        ParamSpec.integer("overhang", 0, 3, 1), firstIsDefault("ridge", "auto", AXIS_U, AXIS_W),
                        direction("high_side", Facing.EAST), ParamSpec.bool("gable_fill", true),
                        ParamSpec.integer("tooth", 2, 8, 3), ParamSpec.integer("monitor_width", 1, 5, 1),
                        ParamSpec.integer("monitor_height", 1, 3, 1), material(ROLE_ROOF)),
                door(),
                part("window", PartCategory.OPENING, BuildPhase.ENVELOPE, VerifyMode.BLOCK_ONLY,
                        "A window opening in a wall: pane, wide or arch.",
                        firstIsDefault(KIND, "pane", "wide", "arch"), ParamSpec.bool("lattice", false),
                        material(ROLE_GLASS)),
                part("stairs", PartCategory.STRUCTURE, BuildPhase.STRUCTURE, VerifyMode.STATE_SUBSET,
                        "A staircase rising in one direction.",
                        ParamSpec.integer("steps", 1, MAX_RUN, 4), ParamSpec.integer(WIDTH, 1, MAX_STRIP, 1),
                        defaultDirection(DIR), material(ROLE_STAIRS)),
                part("ladder", PartCategory.STRUCTURE, BuildPhase.DECORATION, VerifyMode.STATE_SUBSET,
                        "A ladder against a wall.",
                        ParamSpec.integer(HEIGHT, 1, MAX_RUN, 3), defaultDirection("facing")),
                part("catwalk", PartCategory.STRUCTURE, BuildPhase.DECORATION, VerifyMode.BLOCK_ONLY,
                        "A grated walkway with optional railings.",
                        ParamSpec.integer(LENGTH, 1, MAX_SPAN, 6), defaultDirection(DIR),
                        ParamSpec.integer(WIDTH, 1, 5, 2), ParamSpec.bool(RAIL, true), material(ROLE_CATWALK)),
                part("balcony", PartCategory.STRUCTURE, BuildPhase.DECORATION, VerifyMode.BLOCK_ONLY,
                        "A balcony projecting from a wall, with railings.",
                        ParamSpec.integer(WIDTH, 1, 16, 3), ParamSpec.integer(DEPTH, 1, MAX_STRIP, 2),
                        ParamSpec.bool(RAIL, true), material(ROLE_FLOOR)),
                part("railing", PartCategory.DECOR, BuildPhase.DECORATION, VerifyMode.BLOCK_ONLY,
                        "A straight run of fence.",
                        ParamSpec.integer(LENGTH, 1, MAX_SPAN, 3), defaultDirection(DIR),
                        ParamSpec.integer(HEIGHT, 1, 3, 1), material(ROLE_FENCE)),
                part("chimney", PartCategory.STRUCTURE, BuildPhase.STRUCTURE, VerifyMode.STATE_SUBSET,
                        "A brick chimney with an optional cap.",
                        ParamSpec.integer(HEIGHT, 2, MAX_RUN, 6), ParamSpec.integer("size", 1, 3, 1),
                        ParamSpec.bool("cap", true), material(ROLE_CHIMNEY)),
                part("ramp", PartCategory.STRUCTURE, BuildPhase.STRUCTURE, VerifyMode.STATE_SUBSET,
                        "A gentle ramp of alternating slabs.",
                        ParamSpec.integer(LENGTH, 2, MAX_RUN, 6), defaultDirection(DIR),
                        ParamSpec.integer(WIDTH, 1, MAX_STRIP, 2), material(ROLE_RAMP)),
                part("lamp", PartCategory.DECOR, BuildPhase.DECORATION, VerifyMode.STATE_SUBSET,
                        "A light: standing lantern, hanging lantern, lamp post or torch.",
                        firstIsDefault(KIND, "lantern", "hanging", "post", "torch"),
                        ParamSpec.integer(HEIGHT, 1, 6, 2)),
                part("sign", PartCategory.DECOR, BuildPhase.DECORATION, VerifyMode.STATE_SUBSET,
                        "A wall sign with up to four short lines of text.",
                        ParamSpec.text("text", MAX_SIGN_TEXT, null), material(ROLE_SIGN)),
                part("planter", PartCategory.DECOR, BuildPhase.DECORATION, VerifyMode.EXACT,
                        "A flower bed along a wall.", ParamSpec.integer(WIDTH, 1, MAX_STRIP, 3)),
                part("trim", PartCategory.DECOR, BuildPhase.DECORATION, VerifyMode.STATE_SUBSET,
                        "A decorative course along a wall face.",
                        ParamSpec.integer(LENGTH, 1, MAX_SPAN, 3), firstIsDefault(AXIS, "horizontal", "vertical"),
                        blockOrSlab("shape"), material(ROLE_TRIM)),
                part("dock_pad", PartCategory.LOGISTICS, BuildPhase.LOGISTICS, VerifyMode.STATE_SUBSET,
                        "A flat landing pad for airships with a cargo barrel and clear air above.",
                        ParamSpec.integer(WIDTH, 5, MAX_SPAN, 9), ParamSpec.integer(DEPTH, 5, MAX_SPAN, 9),
                        ParamSpec.integer("clearance", 4, MAX_SPAN, 16), ParamSpec.integer("cargo_u", 0, MAX_INDEX, 1),
                        ParamSpec.integer("cargo_w", 0, MAX_INDEX, 1), ParamSpec.bool("marker", true),
                        material(ROLE_PAD)),
                part("road", PartCategory.LOGISTICS, BuildPhase.LOGISTICS, VerifyMode.EXACT,
                        "A straight path of gravel.",
                        ParamSpec.integer(LENGTH, 1, 128, 8), defaultDirection(DIR),
                        ParamSpec.integer(WIDTH, 1, MAX_STRIP, 2), material(ROLE_PATH)));
    }

    /** The door is the one part with volatile block states, so it adds them to the common builder. */
    private static PartType door() {
        return builder("door", PartCategory.OPENING, BuildPhase.ENVELOPE, VerifyMode.STATE_SUBSET,
                "A door opening in a wall: single, double or a wide hangar door.",
                firstIsDefault(KIND, "single", "double", "hangar"), ParamSpec.integer(WIDTH, 3, 9, 5),
                ParamSpec.integer(HEIGHT, 3, 6, 4), firstIsDefault("hinge", "left", "right"), material(ROLE_DOOR))
                .volatileProps(STATE_OPEN, STATE_POWERED).build();
    }

    private static PartType part(String name, PartCategory category, BuildPhase phase, VerifyMode verify,
                                 String visualDescription, ParamSpec... params) {
        return builder(name, category, phase, verify, visualDescription, params).build();
    }

    private static PartType.Builder builder(String name, PartCategory category, BuildPhase phase, VerifyMode verify,
                                            String visualDescription, ParamSpec... params) {
        return PartType.builder(ID_PREFIX + name, category).displayNameKey(DISPLAY_NAME_PREFIX + name)
                .visualDescription(visualDescription).params(params).verify(verify).phase(phase);
    }

    private static ParamSpec material(String defaultRole) {
        return ParamSpec.material(MATERIAL, defaultRole);
    }

    /** An enum parameter whose first value is its default. */
    private static ParamSpec firstIsDefault(String name, String... values) {
        return ParamSpec.enumOf(name, values[0], values);
    }

    private static ParamSpec blockOrSlab(String name) {
        return firstIsDefault(name, "block", "slab");
    }

    private static ParamSpec defaultDirection(String name) {
        return direction(name, Facing.NORTH);
    }

    private static ParamSpec direction(String name, Facing defaultFacing) {
        return ParamSpec.enumOf(name, defaultFacing.lower(), DIRECTIONS);
    }

    /** A direction with no default: guessing one would silently build on the wrong side. */
    private static ParamSpec requiredDirection(String name) {
        return ParamSpec.enumOf(name, null, DIRECTIONS);
    }
}
