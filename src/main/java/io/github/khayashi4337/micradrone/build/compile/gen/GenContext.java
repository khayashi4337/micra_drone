package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.BlockRotation;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.Params;
import io.github.khayashi4337.micradrone.build.parts.PartType;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** What a generator may use: the palette, the canvas, node geometry, and the ways to report a problem. */
public final class GenContext {
    /** The building part: the parent that gives floors, walls and roofs their frame. */
    public static final String STRUCTURE = BuildingParts.ID_PREFIX + "structure";

    // Keys that tell apart several issues of one code on one node.
    static final String KEY_ANCHOR = "anchor";
    static final String KEY_LEVEL = "level";
    /** A part on a wall face whose extent along the wall runs past the wall's end. */
    static final String KEY_EXTENT = "extent";
    private static final String KEY_PARENT = "parent";
    private static final String KEY_FROM = "from";
    private static final String KEY_LENGTH = "length";
    private static final String KEY_CARVE = "carve";

    // Parameter names of the structure and wall parts.
    private static final String P_WIDTH = "width";
    private static final String P_DEPTH = "depth";
    private static final String P_FLOORS = "floors";
    private static final String P_FLOOR_HEIGHT = "floor_height";
    private static final String P_SIDE = "side";
    private static final String P_LEVEL = "level";
    private static final String P_FROM = "from";
    private static final String P_LENGTH = "length";
    private static final String P_HEIGHT = "height";
    private static final String P_PART = "part";
    private static final String P_THICKNESS = "thickness";
    private static final String PART_HALF = "half";
    /** A length or height of 0 means "all of it" (the rest of the side, the storey minus its floor row). */
    private static final int AUTO = 0;
    private static final String NO_PARENT = "なし";

    /** A node with its resolved parameters and its local origin (null for nodes placed on a wall face). */
    public record NodeInfo(PlanNode node, PartType type, Params params, LocalPos origin) {
    }

    private final PartTypeRegistry registry;
    private final Palette palette;
    private final Canvas canvas;
    private final List<Issue> issues;
    private final Map<String, PlanNode> nodes;
    private final Map<String, LocalPos> origins;
    private final Map<String, NodeInfo> infoCache = new HashMap<>();
    private final Map<String, Optional<WallInfo>> walls = new HashMap<>();
    private final Map<LocalPos, String> carvedBy = new HashMap<>();
    private final Set<String> reportedWalls = new HashSet<>();

    public GenContext(PartTypeRegistry registry, Palette palette, Canvas canvas, List<Issue> issues,
                      Map<String, PlanNode> nodes, Map<String, LocalPos> origins) {
        this.registry = registry;
        this.palette = palette;
        this.canvas = canvas;
        this.issues = issues;
        this.nodes = nodes;
        this.origins = origins;
    }

    public Palette palette() {
        return palette;
    }

    public Canvas canvas() {
        return canvas;
    }

    public PlanNode node(String id) {
        return nodes.get(id);
    }

    /** The block a material (a role name or a block id) names, without block states. */
    public BlockSpec plainBlock(String material, PlanNode node) {
        return BlockForms.plain(palette.full(material, node));
    }

    public GenAbort fail(PlanNode node, IssueCode code, String key, String message) {
        return new GenAbort(Issue.of(code, key, List.of(node.id()), message, Map.of(), List.of()), false);
    }

    public NodeInfo info(String id) {
        return infoCache.computeIfAbsent(id, key -> {
            PlanNode n = nodes.get(key);
            PartType type = registry.get(n.type());
            return new NodeInfo(n, type, Params.resolve(type, n.params()), origins.get(key));
        });
    }

    public StructureInfo structureOf(PlanNode node) {
        PlanNode parent = node.parent() == null ? null : nodes.get(node.parent());
        if (parent == null || !parent.type().equals(STRUCTURE)) {
            throw fail(node, IssueCode.E_ANCHOR, KEY_PARENT, node.type() + "は、建屋(" + STRUCTURE + ")の中に置いてください(親: "
                    + (node.parent() == null ? NO_PARENT : node.parent()) + ")");
        }
        return structureInfo(parent);
    }

    private StructureInfo structureInfo(PlanNode structureNode) {
        NodeInfo info = info(structureNode.id());
        LocalPos origin = info.origin();
        if (origin == null) {
            throw fail(structureNode, IssueCode.E_ANCHOR, KEY_ANCHOR, "建屋の位置を決められません");
        }
        Params p = info.params();
        return new StructureInfo(structureNode.id(), origin, p.i(P_WIDTH), p.i(P_DEPTH), p.i(P_FLOORS), p.i(P_FLOOR_HEIGHT));
    }

    /** Refuses a storey number that the building does not have (E-PARAM-RANGE on {@code level}). */
    void checkLevel(PlanNode node, StructureInfo st, int level) {
        if (level >= st.floors()) {
            throw fail(node, IssueCode.E_PARAM_RANGE, KEY_LEVEL, "階(level=" + level + ")が、建屋の階数(" + st.floors() + ")を超えています");
        }
    }

    /** The wall's geometry, or empty if the node is not a valid wall (the wall's own problem is reported once). */
    public Optional<WallInfo> wallInfo(String wallId) {
        return walls.computeIfAbsent(wallId, id -> {
            PlanNode wall = nodes.get(id);
            if (wall == null || !wall.type().equals(BuildingParts.WALL)) {
                return Optional.empty();
            }
            try {
                return Optional.of(buildWallInfo(wall));
            } catch (GenAbort abort) {
                if (reportedWalls.add(id)) {
                    issues.add(abort.issue());
                }
                return Optional.empty();
            }
        });
    }

    private WallInfo buildWallInfo(PlanNode wall) {
        StructureInfo st = structureOf(wall);
        Params p = info(wall.id()).params();
        Facing side = Facing.parse(p.s(P_SIDE));
        int level = p.i(P_LEVEL);
        checkLevel(wall, st, level);
        int sideLen = WallInfo.sideLength(st, side);
        int from = p.i(P_FROM);
        int length = p.i(P_LENGTH) == AUTO ? sideLen - from : p.i(P_LENGTH);
        if (from >= sideLen) {
            throw fail(wall, IssueCode.E_PARAM_RANGE, KEY_FROM, "始点(from=" + from + ")が、壁の側の長さ(" + sideLen + ")以上です");
        }
        if (from + length > sideLen) {
            throw fail(wall, IssueCode.E_PARAM_RANGE, KEY_LENGTH, "壁の端を越えます(from=" + from + " + length=" + length + " > " + sideLen + ")");
        }
        int height = p.i(P_HEIGHT) == AUTO ? st.floorHeight() - WallInfo.FLOOR_ROWS : p.i(P_HEIGHT);
        if (p.s(P_PART).equals(PART_HALF)) {
            height = (height + 1) / 2; // the lower half, rounded up
        }
        int baseV = st.origin().v() + level * st.floorHeight() + WallInfo.FLOOR_ROWS;
        return new WallInfo(wall.id(), st, side, level, baseV, height, p.i(P_THICKNESS), from, length);
    }

    /**
     * The wall a node is attached to by an OnSurface anchor. The anchor may come from a template, which the expander
     * passes through unchecked, so the face, the target and the position on the face are all checked here.
     */
    public WallInfo wallOfAnchor(PlanNode node) {
        if (!(node.anchor() instanceof Anchor.OnSurface s)) {
            throw fail(node, IssueCode.E_ANCHOR, KEY_ANCHOR, node.type() + "は、壁の面(OnSurface)に付けてください");
        }
        if (s.side() != Side.OUTER && s.side() != Side.INNER) {
            throw fail(node, IssueCode.E_ANCHOR, KEY_ANCHOR, "面の側は outer か inner です(" + s.side().lower() + ")");
        }
        WallInfo wall = wallInfo(s.nodeId()).orElseThrow(() -> fail(node, IssueCode.E_OPENING_NO_WALL, KEY_ANCHOR,
                "付ける壁(" + s.nodeId() + ")が、施工できる壁ではありません"));
        if (s.u() < 0 || s.u() >= wall.length() || s.v() < 0 || s.v() >= wall.height()) {
            throw fail(node, IssueCode.E_OPENING_NO_WALL, KEY_ANCHOR, "壁(" + wall.id() + ")の面の上の位置(u=" + s.u() + ", v=" + s.v()
                    + ")が、壁の外です(u は0〜" + (wall.length() - 1) + "、v は0〜" + (wall.height() - 1) + ")");
        }
        return wall;
    }

    private Rot rotOf(PlanNode node) {
        return node.anchor() instanceof Anchor.Absolute a ? a.rot() : Rot.NONE;
    }

    public void emit(PlanNode node, int du, int dv, int dw, BlockSpec block) {
        emit(node, du, dv, dw, block, Map.of());
    }

    /** Places a block at an offset from the node's origin; the node's rotation and mirror apply. */
    public void emit(PlanNode node, int du, int dv, int dw, BlockSpec block, Map<String, String> blockEntity) {
        put(node, placed(node, du, dv, dw), BlockRotation.transform(block, rotOf(node)), blockEntity, null, null);
    }

    /** Where an offset from the node's origin ends up after the node's rotation and mirror. */
    public LocalPos placed(PlanNode node, int du, int dv, int dw) {
        LocalPos origin = info(node.id()).origin();
        if (origin == null) {
            throw fail(node, IssueCode.E_ANCHOR, KEY_ANCHOR, "位置を決められません");
        }
        LocalPos off = rotOf(node).apply(new LocalPos(du, dv, dw));
        return origin.plus(off.u(), off.v(), off.w());
    }

    public void emitAbs(PlanNode node, LocalPos pos, BlockSpec block) {
        emitAbs(node, pos, block, null, null, Map.of());
    }

    /** {@code mergeVariant} tells walls of different sides apart: only those may share a corner cell (same group, same block). */
    public void emitAbs(PlanNode node, LocalPos pos, BlockSpec block, String mergeGroup, String mergeVariant,
                        Map<String, String> blockEntity) {
        put(node, pos, block, blockEntity, mergeGroup, mergeVariant);
    }

    private void put(PlanNode node, LocalPos pos, BlockSpec block, Map<String, String> blockEntity, String mergeGroup,
                     String mergeVariant) {
        PartType type = registry.get(node.type());
        canvas.put(new Canvas.Cell(pos, block, BlockForms.verifyFor(block), type.phase(), blockEntity, node.id(), mergeGroup,
                mergeVariant));
    }

    /**
     * Removes the cells an opening replaces. Refuses (and changes nothing) if any is not a wall cell of this building or
     * was already carved. Every wall of a building shares one merge group, so a cell of another wall segment or storey of
     * the same building passes too: an opening that must stay on its own wall checks its extent first (OpeningSpot.carved).
     */
    public void carve(PlanNode opener, WallInfo wall, List<LocalPos> cells) {
        int notWall = 0;
        LocalPos firstBad = null;
        boolean overlapped = false;
        for (LocalPos pos : cells) {
            String earlier = carvedBy.get(pos);
            if (earlier != null) {
                canvas.recordOverlap(earlier, opener.id(), pos);
                overlapped = true;
                continue;
            }
            Canvas.Cell c = canvas.get(pos);
            // a corner cell shared with the neighbouring wall belongs to whichever wall was generated first: both are this building's walls
            boolean ownWall = c != null && (c.ownerId().equals(wall.id()) || wall.cornerGroup().equals(c.mergeGroup()));
            if (!ownWall) {
                notWall++;
                if (firstBad == null) {
                    firstBad = pos;
                }
            }
        }
        if (overlapped) {
            throw new GenAbort(Issue.of(IssueCode.E_OVERLAP, KEY_CARVE, List.of(opener.id()),
                    opener.id() + "の開口部が、別の開口部と重なっています", Map.of(), List.of()), false);
        }
        if (notWall > 0) {
            throw new GenAbort(Issue.of(IssueCode.E_OPENING_NO_WALL, "", List.of(opener.id()),
                    opener.id() + "の開口部が、壁(" + wall.id() + ")の外にはみ出しています(壁でないマスが" + notWall + "個。最初は "
                            + Canvas.posText(firstBad) + ")",
                    Map.of(Canvas.DATA_COUNT, String.valueOf(notWall)), List.of()), false);
        }
        for (LocalPos pos : cells) {
            canvas.remove(pos);
            carvedBy.put(pos, opener.id());
        }
    }
}
