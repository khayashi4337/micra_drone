package io.github.khayashi4337.micradrone.build.parts;

/**
 * The palette roles the parts declare (design doc 01, section 2.2). A generator asks the palette for a role by
 * these names, so they live here once and both sides share the spelling.
 */
public final class Roles {
    public static final String WALL = "wall";
    public static final String FLOOR = "floor";
    public static final String ROOF = "roof";
    public static final String FOUNDATION = "foundation";
    public static final String PILLAR = "pillar";
    public static final String BEAM = "beam";
    public static final String TRIM = "trim";
    public static final String GLASS = "glass";
    public static final String DOOR = "door";
    public static final String GATE = "gate";
    public static final String FENCE = "fence";
    public static final String STAIRS = "stairs";
    public static final String RAMP = "ramp";
    public static final String CATWALK = "catwalk";
    public static final String CHIMNEY = "chimney";
    public static final String PATH = "path";
    public static final String PAD = "pad";
    public static final String MARKER = "marker";
    public static final String CARGO = "cargo";
    public static final String SIGN = "sign";
    public static final String PLANTER = "planter";
    public static final String PLANT = "plant";

    private Roles() {
    }
}
