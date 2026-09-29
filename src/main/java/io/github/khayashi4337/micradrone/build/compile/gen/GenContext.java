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
import io.github.khayashi4337.micradrone.build.parts.PartParams;
import io.github.khayashi4337.micradrone.build.parts.PartType;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

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

    // Parameter names of the structure and wall parts.
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
    private final OpeningResolver openings = new OpeningResolver();
    private final Set<String> reportedWalls = new HashSet<>();
    private final Set<String> refused = new HashSet<>();
    /** Non-null while an accepted opening's emitter runs: its cells are collected, not placed (see resolveOpenings). */
    private List<Canvas.Cell> captured;

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

    /** Every node of the plan, for the passes that need the whole picture (the opening claims, for instance). */
    Map<String, PlanNode> nodes() {
        return nodes;
    }

    /** The issues reported so far: passes that run outside a node (the claim resolver) add theirs here. */
    List<Issue> issues() {
        return issues;
    }

    /** Records that the node's generator refused it (its issue is reported), so that no later pass works on it. */
    public void markRefused(String nodeId) {
        refused.add(nodeId);
    }

    public boolean isRefused(String nodeId) {
        return refused.contains(nodeId);
    }

    /**
     * The block a material (a role name or a block id) names, without block states. The block is recorded as
     * used by the node, so a block the policy forbids fails it later with E-BLOCK-FORBIDDEN.
     */
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
        return new StructureInfo(structureNode.id(), origin, p.i(PartParams.WIDTH), p.i(PartParams.DEPTH), p.i(PartParams.FLOORS), p.i(PartParams.FLOOR_HEIGHT));
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
        Facing side = Facing.parse(p.s(PartParams.SIDE));
        int level = p.i(PartParams.LEVEL);
        checkLevel(wall, st, level);
        int sideLen = WallInfo.sideLength(st, side);
        int from = p.i(PartParams.FROM);
        int length = p.i(PartParams.LENGTH) == AUTO ? sideLen - from : p.i(PartParams.LENGTH);
        if (from >= sideLen) {
            throw fail(wall, IssueCode.E_PARAM_RANGE, KEY_FROM, "始点(from=" + from + ")が、壁の側の長さ(" + sideLen + ")以上です");
        }
        if (from + length > sideLen) {
            throw fail(wall, IssueCode.E_PARAM_RANGE, KEY_LENGTH, "壁の端を越えます(from=" + from + " + length=" + length + " > " + sideLen + ")");
        }
        int height = p.i(PartParams.HEIGHT) == AUTO ? st.floorHeight() - WallInfo.FLOOR_ROWS : p.i(PartParams.HEIGHT);
        if (p.s(PartParams.PART).equals(PART_HALF)) {
            height = (height + 1) / 2; // the lower half, rounded up
        }
        int baseV = st.origin().v() + level * st.floorHeight() + WallInfo.FLOOR_ROWS;
        return new WallInfo(wall.id(), st, side, level, baseV, height, p.i(PartParams.THICKNESS), from, length);
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
        if (s.u() < 0 || !wall.fitsAlong(s.u(), 1) || s.v() < 0 || !wall.fitsUp(s.v(), 1)) {
            throw fail(node, IssueCode.E_OPENING_NO_WALL, KEY_ANCHOR, "壁(" + wall.id() + ")の面の上の位置(u=" + s.u() + ", v=" + s.v()
                    + ")が、壁の外です(u は0〜" + (wall.length() - 1) + "、v は0〜" + (wall.height() - 1) + ")");
        }
        return wall;
    }

    /**
     * Refuses (E-ANCHOR on {@code extent}) a span of {@code span} cells that starts at position {@code i} along
     * {@code wall} and runs past the wall's end. {@code what} names the part in the message. A generator calls it
     * before anything is looked up and before the first emit, so a refusal leaves nothing behind. The anchor's own
     * cell is already on the wall ({@link #wallOfAnchor} checked it), so only the far end can be past it.
     */
    public void requireAlongWall(PlanNode node, WallInfo wall, int i, int span, String what) {
        if (!wall.fitsAlong(i, span)) {
            throw fail(node, IssueCode.E_ANCHOR, KEY_EXTENT, node.id() + "の" + what + "(u=" + i + "から幅" + span
                    + ")が、壁(" + wall.id() + ")の外にはみ出しています(壁は長さ" + wall.length() + ")");
        }
    }

    /**
     * The upward mirror of {@link #requireAlongWall}: {@code span} rows that start at row {@code v} and run past
     * the wall's top are refused.
     */
    public void requireUpWall(PlanNode node, WallInfo wall, int v, int span, String what) {
        if (!wall.fitsUp(v, span)) {
            throw fail(node, IssueCode.E_ANCHOR, KEY_EXTENT, node.id() + "の" + what + "(v=" + v + "から高さ" + span
                    + ")が、壁(" + wall.id() + ")の外にはみ出しています(壁は高さ" + wall.height() + ")");
        }
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

    /** {@code mergeVariant} tells walls on different axes apart: only those may share a corner cell (same group, same block). */
    public void emitAbs(PlanNode node, LocalPos pos, BlockSpec block, String mergeGroup, String mergeVariant,
                        Map<String, String> blockEntity) {
        put(node, pos, block, blockEntity, mergeGroup, mergeVariant);
    }

    private void put(PlanNode node, LocalPos pos, BlockSpec block, Map<String, String> blockEntity, String mergeGroup,
                     String mergeVariant) {
        PartType type = registry.get(node.type());
        Canvas.Cell cell = new Canvas.Cell(pos, block, BlockForms.verifyFor(block), type.phase(), blockEntity, node.id(),
                mergeGroup, mergeVariant);
        if (captured != null) {
            captured.add(cell);
        } else {
            canvas.put(cell);
        }
    }

    /**
     * Registers an opening's claim — the spot it takes on its wall, what it puts back and where, and the emitter that
     * places those blocks if the claim is accepted. Nothing is carved or placed yet: the claims of every opening are
     * resolved together by {@link #resolveOpenings}, so the outcome cannot depend on the order they were claimed in.
     */
    public void claimOpening(PlanNode node, OpeningSpot spot, OpeningResolver.Contract contract,
                             Map<LocalPos, OpeningResolver.Perm> fills, Consumer<GenContext> emit) {
        openings.add(new OpeningResolver.Claim(node, spot, spot.cells(), contract, fills, emit));
    }

    /** Registers a claim as it is — the form the resolver's own checks take in tests. */
    void claimOpening(OpeningResolver.Claim claim) {
        openings.add(claim);
    }

    /**
     * Resolves every collected opening claim in one batch and applies the accepted ones to the canvas. Called once,
     * after the CARVE stage has gathered them all.
     */
    public void resolveOpenings() {
        openings.resolve(this);
    }

    /**
     * The cells the given emitter would place, collected instead of placed: an accepted opening's fills go down only
     * once its whole tunnel is carved, and a refusal inside the emitter leaves the wall untouched.
     */
    List<Canvas.Cell> captureEmit(Consumer<GenContext> emit) {
        List<Canvas.Cell> buffer = new ArrayList<>();
        captured = buffer;
        try {
            emit.accept(this);
        } finally {
            captured = null;
        }
        return buffer;
    }
}
