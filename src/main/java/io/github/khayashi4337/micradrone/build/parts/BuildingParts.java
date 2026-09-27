package io.github.khayashi4337.micradrone.build.parts;

import io.github.khayashi4337.micradrone.build.model.Facing;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The building parts (micra:*): what a plan may build from vanilla blocks, and the default palette that turns a
 * role such as "wall" into a block. Parameters, ranges, defaults and phases follow design doc 05, section 1.1.1;
 * BuildingPartsTest pins them to that table.
 */
public final class BuildingParts {
    public static final String ID_PREFIX = "micra:";

    private static final String WALL_NAME = "wall";
    /** The wall part. It is the only part whose surface other parts (doors, windows, ...) can be placed on. */
    public static final String WALL = ID_PREFIX + WALL_NAME;

    // Names of the other parts that ROTATION_UNSUPPORTED lists; the part definitions use the same constants.
    private static final String STRUCTURE_NAME = "structure";
    private static final String FOUNDATION_NAME = "foundation";
    private static final String FLOOR_NAME = "floor";
    private static final String ROOF_NAME = "roof";
    private static final String DOOR_NAME = "door";
    private static final String WINDOW_NAME = "window";
    private static final String SIGN_NAME = "sign";
    private static final String PLANTER_NAME = "planter";
    private static final String TRIM_NAME = "trim";
    private static final String BALCONY_NAME = "balcony";

    private static final String DISPLAY_NAME_PREFIX = "micradrone.part.";
    private static final String BLOCK_NAMESPACE = "minecraft:";

    // Palette roles live in Roles, parameter names in PartParams: a generator and this file cannot spell a name two ways.

    // Blocks that serve more than one role.
    private static final String STONE_BRICKS = "stone_bricks";
    private static final String OAK_PLANKS = "oak_planks";

    // Volatile block states of a door: they change while the world runs and must not fail a verification.
    private static final String STATE_OPEN = "open";
    private static final String STATE_POWERED = "powered";

    // Limits that recur across parts with the same meaning.
    /** Longest side of a structure and longest straight run of a flat part, in blocks. */
    private static final int MAX_SPAN = 64;
    /** Largest u or w position inside a structure. */
    private static final int MAX_INDEX = MAX_SPAN - 1;
    /** Open air required above a dock pad, in blocks. */
    private static final int MAX_CLEARANCE = MAX_SPAN;
    /** Longest straight road, in blocks. */
    private static final int MAX_ROAD_LENGTH = 128;
    private static final int MAX_FLOORS = 8;
    private static final int MAX_LEVEL = MAX_FLOORS - 1;
    /** Longest column, ladder, staircase, ramp or chimney, in blocks. */
    private static final int MAX_RUN = 32;
    /** Widest strip, border or foundation depth, in blocks. */
    private static final int MAX_STRIP = 8;
    /** A sign holds at most this many lines of text. */
    public static final int SIGN_MAX_LINES = 4;
    /** A sign line holds at most this many characters. */
    public static final int SIGN_MAX_LINE_CHARS = 15;
    /** The longest text the sign takes: four full lines plus the three "|" separators between them. */
    private static final int MAX_SIGN_TEXT = SIGN_MAX_LINES * SIGN_MAX_LINE_CHARS + (SIGN_MAX_LINES - 1);
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

    /**
     * Parts that cannot take a rotation or mirror: they are laid out from their parent's footprint or a wall's
     * face, so turning them alone would detach them. A building's direction is set by the site's facing.
     */
    public static final Set<String> ROTATION_UNSUPPORTED = Collections.unmodifiableSortedSet(new TreeSet<>(Set.of(
            ID_PREFIX + STRUCTURE_NAME, ID_PREFIX + FOUNDATION_NAME, ID_PREFIX + FLOOR_NAME, WALL, ID_PREFIX + ROOF_NAME,
            ID_PREFIX + DOOR_NAME, ID_PREFIX + WINDOW_NAME, ID_PREFIX + SIGN_NAME, ID_PREFIX + PLANTER_NAME,
            ID_PREFIX + TRIM_NAME, ID_PREFIX + BALCONY_NAME)));

    private static final PartTypeRegistry REGISTRY = buildRegistry();

    private BuildingParts() {
    }

    public static PartTypeRegistry registry() {
        return REGISTRY;
    }

    private static Map<String, String> defaultPalette() {
        Map<String, String> roles = Map.ofEntries(
                role(Roles.WALL, STONE_BRICKS), role(Roles.FLOOR, OAK_PLANKS), role(Roles.ROOF, OAK_PLANKS),
                role(Roles.FOUNDATION, "cobblestone"), role(Roles.PILLAR, STONE_BRICKS), role(Roles.BEAM, "oak_log"),
                role(Roles.TRIM, STONE_BRICKS), role(Roles.GLASS, "glass_pane"), role(Roles.DOOR, "oak_door"),
                role(Roles.GATE, "oak_fence_gate"), role(Roles.FENCE, "oak_fence"), role(Roles.STAIRS, "oak_stairs"),
                role(Roles.RAMP, "stone"), role(Roles.CATWALK, "iron_trapdoor"), role(Roles.CHIMNEY, "bricks"),
                role(Roles.PATH, "gravel"), role(Roles.PAD, "smooth_stone"), role(Roles.MARKER, "yellow_concrete"),
                role(Roles.CARGO, "barrel"), role(Roles.SIGN, "oak_wall_sign"), role(Roles.PLANTER, "dirt"),
                role(Roles.PLANT, "poppy"));
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
                part(STRUCTURE_NAME, PartCategory.STRUCTURE, BuildPhase.STRUCTURE, VerifyMode.EXACT,
                        "A building: a footprint with floors, the parent of its walls, floors and roof.",
                        ParamSpec.integer(PartParams.WIDTH, 3, MAX_SPAN, 7), ParamSpec.integer(PartParams.DEPTH, 3, MAX_SPAN, 7),
                        ParamSpec.integer(PartParams.FLOORS, 1, MAX_FLOORS, 1), ParamSpec.integer(PartParams.FLOOR_HEIGHT, 3, 8, 4)),
                part(FOUNDATION_NAME, PartCategory.STRUCTURE, BuildPhase.STRUCTURE, VerifyMode.EXACT,
                        "A solid base under the building.",
                        ParamSpec.integer(PartParams.MARGIN, 0, MAX_STRIP, 0), ParamSpec.integer(PartParams.DEPTH, 1, MAX_STRIP, 1),
                        material(Roles.FOUNDATION)),
                part(FLOOR_NAME, PartCategory.STRUCTURE, BuildPhase.STRUCTURE, VerifyMode.STATE_SUBSET,
                        "A floor of blocks or slabs with optional holes for stairs.",
                        ParamSpec.integer(PartParams.LEVEL, 0, MAX_LEVEL, 0), blockOrSlab(PartParams.KIND),
                        ParamSpec.intList(PartParams.HOLES, 0, MAX_INDEX, MAX_HOLE_VALUES), material(Roles.FLOOR)),
                part(WALL_NAME, PartCategory.STRUCTURE, BuildPhase.ENVELOPE, VerifyMode.EXACT,
                        "A wall along one side of a building.",
                        requiredDirection(PartParams.SIDE), ParamSpec.integer(PartParams.LEVEL, 0, MAX_LEVEL, 0),
                        ParamSpec.integer(PartParams.HEIGHT, 0, 16, 0), ParamSpec.integer(PartParams.THICKNESS, 1, 3, 1),
                        ParamSpec.integer(PartParams.FROM, 0, MAX_INDEX, 0), ParamSpec.integer(PartParams.LENGTH, 0, MAX_SPAN, 0),
                        firstIsDefault(PartParams.PART, "full", "half"), material(Roles.WALL)),
                part("pillar", PartCategory.STRUCTURE, BuildPhase.STRUCTURE, VerifyMode.EXACT,
                        "A column with an optional base and capital.",
                        ParamSpec.integer(PartParams.HEIGHT, 1, MAX_RUN, 4), ParamSpec.bool(PartParams.BASE, true),
                        ParamSpec.bool(PartParams.CAPITAL, true), material(Roles.PILLAR)),
                part("beam", PartCategory.STRUCTURE, BuildPhase.STRUCTURE, VerifyMode.STATE_SUBSET,
                        "A straight horizontal or vertical beam.",
                        firstIsDefault(PartParams.AXIS, PartParams.AXIS_U, PartParams.AXIS_V, PartParams.AXIS_W), ParamSpec.integer(PartParams.LENGTH, 1, MAX_SPAN, 3),
                        material(Roles.BEAM)),
                part(ROOF_NAME, PartCategory.ROOF, BuildPhase.ENVELOPE, VerifyMode.STATE_SUBSET,
                        "A roof of stairs and slabs: gable, hip, flat, shed, sawtooth or monitor.",
                        firstIsDefault(PartParams.KIND, "gable", "hip", "flat", "shed", "sawtooth", "monitor"),
                        ParamSpec.integer(PartParams.OVERHANG, 0, 3, 1), firstIsDefault(PartParams.RIDGE, "auto", PartParams.AXIS_U, PartParams.AXIS_W),
                        direction(PartParams.HIGH_SIDE, Facing.EAST), ParamSpec.bool(PartParams.GABLE_FILL, true),
                        ParamSpec.integer(PartParams.TOOTH, 2, 8, 3), ParamSpec.integer(PartParams.MONITOR_WIDTH, 1, 5, 1),
                        ParamSpec.integer(PartParams.MONITOR_HEIGHT, 1, 3, 1), material(Roles.ROOF)),
                door(),
                part(WINDOW_NAME, PartCategory.OPENING, BuildPhase.ENVELOPE, VerifyMode.BLOCK_ONLY,
                        "A window opening in a wall: pane, wide or arch.",
                        firstIsDefault(PartParams.KIND, "pane", "wide", "arch"), ParamSpec.bool(PartParams.LATTICE, false),
                        material(Roles.GLASS)),
                part("stairs", PartCategory.STRUCTURE, BuildPhase.STRUCTURE, VerifyMode.STATE_SUBSET,
                        "A staircase rising in one direction.",
                        ParamSpec.integer(PartParams.STEPS, 1, MAX_RUN, 4), ParamSpec.integer(PartParams.WIDTH, 1, MAX_STRIP, 1),
                        defaultDirection(PartParams.DIR), material(Roles.STAIRS)),
                part("ladder", PartCategory.STRUCTURE, BuildPhase.DECORATION, VerifyMode.STATE_SUBSET,
                        "A ladder against a wall.",
                        ParamSpec.integer(PartParams.HEIGHT, 1, MAX_RUN, 3), defaultDirection(PartParams.FACING)),
                part("catwalk", PartCategory.STRUCTURE, BuildPhase.DECORATION, VerifyMode.BLOCK_ONLY,
                        "A walkway with optional railings.",
                        ParamSpec.integer(PartParams.LENGTH, 1, MAX_SPAN, 6), defaultDirection(PartParams.DIR),
                        ParamSpec.integer(PartParams.WIDTH, 1, 5, 2), ParamSpec.bool(PartParams.RAIL, true), material(Roles.CATWALK)),
                part(BALCONY_NAME, PartCategory.STRUCTURE, BuildPhase.DECORATION, VerifyMode.BLOCK_ONLY,
                        "A balcony projecting from a wall, with railings.",
                        ParamSpec.integer(PartParams.WIDTH, 1, 16, 3), ParamSpec.integer(PartParams.DEPTH, 1, MAX_STRIP, 2),
                        ParamSpec.bool(PartParams.RAIL, true), material(Roles.FLOOR)),
                part("railing", PartCategory.DECOR, BuildPhase.DECORATION, VerifyMode.BLOCK_ONLY,
                        "A straight low railing.",
                        ParamSpec.integer(PartParams.LENGTH, 1, MAX_SPAN, 3), defaultDirection(PartParams.DIR),
                        ParamSpec.integer(PartParams.HEIGHT, 1, 3, 1), material(Roles.FENCE)),
                part("chimney", PartCategory.STRUCTURE, BuildPhase.STRUCTURE, VerifyMode.STATE_SUBSET,
                        "A chimney with an optional cap.",
                        ParamSpec.integer(PartParams.HEIGHT, 2, MAX_RUN, 6), ParamSpec.integer(PartParams.SIZE, 1, 3, 1),
                        ParamSpec.bool(PartParams.CAP, true), material(Roles.CHIMNEY)),
                part("ramp", PartCategory.STRUCTURE, BuildPhase.STRUCTURE, VerifyMode.STATE_SUBSET,
                        "A gentle ramp of alternating slabs.",
                        ParamSpec.integer(PartParams.LENGTH, 2, MAX_RUN, 6), defaultDirection(PartParams.DIR),
                        ParamSpec.integer(PartParams.WIDTH, 1, MAX_STRIP, 2), material(Roles.RAMP)),
                part("lamp", PartCategory.DECOR, BuildPhase.DECORATION, VerifyMode.STATE_SUBSET,
                        "A light: standing lantern, hanging lantern, lamp post or torch.",
                        firstIsDefault(PartParams.KIND, "lantern", "hanging", "post", "torch"),
                        ParamSpec.integer(PartParams.HEIGHT, 1, 6, 2)),
                part(SIGN_NAME, PartCategory.DECOR, BuildPhase.DECORATION, VerifyMode.STATE_SUBSET,
                        "A wall sign with up to four short lines of text.",
                        ParamSpec.text(PartParams.TEXT, MAX_SIGN_TEXT, null), material(Roles.SIGN)),
                part(PLANTER_NAME, PartCategory.DECOR, BuildPhase.DECORATION, VerifyMode.EXACT,
                        "A flower bed along a wall.", ParamSpec.integer(PartParams.WIDTH, 1, MAX_STRIP, 3)),
                part(TRIM_NAME, PartCategory.DECOR, BuildPhase.DECORATION, VerifyMode.STATE_SUBSET,
                        "A decorative course along a wall face.",
                        ParamSpec.integer(PartParams.LENGTH, 1, MAX_SPAN, 3), firstIsDefault(PartParams.AXIS, "horizontal", "vertical"),
                        blockOrSlab(PartParams.SHAPE), material(Roles.TRIM)),
                part("dock_pad", PartCategory.LOGISTICS, BuildPhase.LOGISTICS, VerifyMode.STATE_SUBSET,
                        "A flat landing pad for airships with a cargo spot and clear air above.",
                        ParamSpec.integer(PartParams.WIDTH, 5, MAX_SPAN, 9), ParamSpec.integer(PartParams.DEPTH, 5, MAX_SPAN, 9),
                        ParamSpec.integer(PartParams.CLEARANCE, 4, MAX_CLEARANCE, 16), ParamSpec.integer(PartParams.CARGO_U, 0, MAX_INDEX, 1),
                        ParamSpec.integer(PartParams.CARGO_W, 0, MAX_INDEX, 1), ParamSpec.bool(PartParams.MARKER, true),
                        material(Roles.PAD)),
                part("road", PartCategory.LOGISTICS, BuildPhase.LOGISTICS, VerifyMode.EXACT,
                        "A straight path.",
                        ParamSpec.integer(PartParams.LENGTH, 1, MAX_ROAD_LENGTH, 8), defaultDirection(PartParams.DIR),
                        ParamSpec.integer(PartParams.WIDTH, 1, MAX_STRIP, 2), material(Roles.PATH)));
    }

    /** The door is the one part with volatile block states, so it adds them to the common builder. */
    private static PartType door() {
        return builder(DOOR_NAME, PartCategory.OPENING, BuildPhase.ENVELOPE, VerifyMode.STATE_SUBSET,
                "A door opening in a wall: single, double or a wide hangar door.",
                firstIsDefault(PartParams.KIND, "single", "double", "hangar"), ParamSpec.integer(PartParams.WIDTH, 3, 9, 5),
                ParamSpec.integer(PartParams.HEIGHT, 3, 6, 4), firstIsDefault(PartParams.HINGE, "left", "right"), material(Roles.DOOR))
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
        return ParamSpec.material(PartParams.MATERIAL, defaultRole);
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
