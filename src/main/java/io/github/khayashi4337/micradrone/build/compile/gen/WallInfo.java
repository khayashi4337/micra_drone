package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.LocalPos;

/**
 * Geometry of one wall. {@code side} is the direction the wall faces outward: NORTH is the wall at w=depth-1,
 * SOUTH at w=0, EAST at u=width-1, WEST at u=0. Cells are addressed by (i along the wall, layer from the outside,
 * row from the bottom).
 */
public record WallInfo(String id, StructureInfo structure, Facing side, int level, int baseV, int height, int thickness,
                       int from, int length) {
    /** Prefix of the merge group shared by the walls of one building (see {@link #cornerGroup}). */
    private static final String CORNER_GROUP_PREFIX = "wall:";
    /** The storey's floor takes the lowest row, so a wall starts one row above it (row 0) and is one row shorter. */
    static final int FLOOR_ROWS = 1;
    /** {@code layer} -1 of a wall is the first cell outside its outermost layer (see {@link #cell}). */
    public static final int OUTSIDE_LAYER = -1;

    public Facing outward() {
        return side;
    }

    /**
     * The {@link #cell} layer a part attached to a face of this wall goes in: {@link #OUTSIDE_LAYER}, the first
     * cell out, for the outer face; {@code thickness}, the first cell inside, for the inner face.
     */
    public int faceLayer(boolean outer) {
        return outer ? OUTSIDE_LAYER : thickness;
    }

    /** North and south walls run along u (east); east and west walls along w (north). */
    static boolean runsAlongU(Facing side) {
        return side == Facing.NORTH || side == Facing.SOUTH;
    }

    /** The length of the building's side that a wall facing {@code side} stands on. */
    static int sideLength(StructureInfo structure, Facing side) {
        return runsAlongU(side) ? structure.width() : structure.depth();
    }

    /** The merge group of the walls of one building: two of its walls on different sides may share a corner cell. */
    static String cornerGroup(String structureId) {
        return CORNER_GROUP_PREFIX + structureId;
    }

    public String cornerGroup() {
        return cornerGroup(structure.id());
    }

    /** The direction in which the face coordinate u grows: east for north/south walls, north for east/west walls. */
    public Facing along() {
        return runsAlongU(side) ? Facing.EAST : Facing.NORTH;
    }

    /**
     * The axis the wall runs along: "u" for north and south walls, "w" for east and west ones. A corner cell is one
     * that two perpendicular walls of the same building both cover, so it may only ever join cells whose axes differ.
     */
    public String axis() {
        return runsAlongU(side) ? "u" : "w";
    }

    /**
     * Whether {@code p} is a cell of this wall: along it between {@code from} and {@code from + length}, within its
     * thickness and its height — the rectangular prism the wall fills. The corner where a perpendicular wall reaches
     * is inside both walls' prisms.
     */
    public boolean contains(LocalPos p) {
        return prismCoords(p) != null;
    }

    /** The position along the wall's span (0-based from {@link #from}) of {@code p}, or -1 when outside the prism. */
    public int alongAt(LocalPos p) {
        int[] coords = prismCoords(p);
        return coords == null ? -1 : coords[0];
    }

    /** The layer of {@code p} (0 = the outer face, toward the wall's side), or -1 when outside the prism. */
    public int layerAt(LocalPos p) {
        int[] coords = prismCoords(p);
        return coords == null ? -1 : coords[1];
    }

    /** {@code p}'s [along, across] coordinates in the wall's prism, or {@code null} when the prism does not hold it. */
    private int[] prismCoords(LocalPos p) {
        LocalPos o = structure.origin();
        if (p.v() < baseV || p.v() >= baseV + height) {
            return null;
        }
        int along = (runsAlongU(side) ? p.u() - o.u() : p.w() - o.w()) - from;
        int across = switch (side) {
            case SOUTH -> p.w() - o.w();
            case NORTH -> structure.depth() - 1 - (p.w() - o.w());
            case EAST -> structure.width() - 1 - (p.u() - o.u());
            case WEST -> p.u() - o.u();
        };
        return along >= 0 && along < length && across >= 0 && across < thickness ? new int[] {along, across} : null;
    }

    /**
     * Whether {@code span} cells that start at position {@code i} along the wall all lie within the wall: the last one,
     * {@code i + span - 1}, is at most the wall's last position, {@code length - 1}. Positions count from the wall's own
     * start ({@code from}), so this compares with the wall's length, not with the length of the side it stands on (a wall
     * may cover only a part of its side). It looks at nothing else: the caller has a position {@code i >= 0} on this wall
     * (see {@link GenContext#wallOfAnchor}), and rows are its own concern. A part that is attached to a wall face, such as an
     * opening or a balcony, uses it to keep its extent along the wall inside the wall.
     */
    public boolean fitsAlong(int i, int span) {
        return (long) i + span <= length;
    }

    /**
     * Whether {@code span} rows that start at row {@code v} all lie within the wall: the last one,
     * {@code v + span - 1}, is at most the wall's top row, {@code height - 1}. The mirror of {@link #fitsAlong}
     * for the upward direction.
     */
    public boolean fitsUp(int v, int span) {
        return (long) v + span <= height;
    }

    /**
     * The row, in {@link #cell}'s numbering, of the cells {@code above} rows above the storey's floor. The floor's own level
     * ({@code above} 0) is the row just below the wall's lowest row, so it is row -{@link #FLOOR_ROWS} here, and the wall's
     * lowest row (row 0) is {@code above} = {@link #FLOOR_ROWS}.
     */
    public int rowAboveStoreyFloor(int above) {
        return above - FLOOR_ROWS;
    }

    /** {@code layer} 0 is the outermost layer, thickness-1 the innermost; -1 is the first cell outside the wall. */
    public LocalPos cell(int i, int layer, int row) {
        LocalPos o = structure.origin();
        int v = baseV + row;
        return switch (side) {
            case NORTH -> new LocalPos(o.u() + from + i, v, o.w() + structure.depth() - 1 - layer);
            case SOUTH -> new LocalPos(o.u() + from + i, v, o.w() + layer);
            case EAST -> new LocalPos(o.u() + structure.width() - 1 - layer, v, o.w() + from + i);
            case WEST -> new LocalPos(o.u() + layer, v, o.w() + from + i);
        };
    }
}
