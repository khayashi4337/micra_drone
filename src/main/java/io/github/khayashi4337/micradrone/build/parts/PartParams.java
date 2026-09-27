package io.github.khayashi4337.micradrone.build.parts;

/**
 * The parameter names of the part registry (design doc 05, section 1.1.1). The generators read a part's values by
 * these names, so the names live here once: a declaration in {@link BuildingParts} and a read in a generator can
 * never spell the same parameter two ways.
 */
public final class PartParams {
    public static final String WIDTH = "width";
    public static final String DEPTH = "depth";
    public static final String HEIGHT = "height";
    public static final String LENGTH = "length";
    public static final String LEVEL = "level";
    public static final String KIND = "kind";
    public static final String DIR = "dir";
    public static final String RAIL = "rail";
    public static final String AXIS = "axis";
    public static final String FACING = "facing";
    public static final String MATERIAL = "material";

    // structure / wall
    public static final String FLOORS = "floors";
    public static final String FLOOR_HEIGHT = "floor_height";
    public static final String MARGIN = "margin";
    public static final String HOLES = "holes";
    public static final String SIDE = "side";
    public static final String THICKNESS = "thickness";
    public static final String FROM = "from";
    public static final String PART = "part";

    // part-specific
    public static final String BASE = "base";
    public static final String CAPITAL = "capital";
    public static final String SIZE = "size";
    public static final String CAP = "cap";
    public static final String STEPS = "steps";
    public static final String TEXT = "text";
    public static final String HINGE = "hinge";
    public static final String SHAPE = "shape";
    public static final String LATTICE = "lattice";
    public static final String CLEARANCE = "clearance";
    public static final String CARGO_U = "cargo_u";
    public static final String CARGO_W = "cargo_w";
    public static final String MARKER = "marker";

    // roof
    public static final String OVERHANG = "overhang";
    public static final String RIDGE = "ridge";
    public static final String HIGH_SIDE = "high_side";
    public static final String GABLE_FILL = "gable_fill";
    public static final String TOOTH = "tooth";
    public static final String MONITOR_WIDTH = "monitor_width";
    public static final String MONITOR_HEIGHT = "monitor_height";

    /** The three local axes, as the values of an {@link #AXIS} or {@link #RIDGE} parameter. */
    public static final String AXIS_U = "u";
    public static final String AXIS_V = "v";
    public static final String AXIS_W = "w";

    private PartParams() {
    }
}
