package io.github.khayashi4337.micradrone.build.compile.gen;

import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.node;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.onWall;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.shell;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.CompileFixtures;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.parts.Params;
import io.github.khayashi4337.micradrone.build.parts.PartType;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import io.github.khayashi4337.micradrone.build.plan.Origins;
import io.github.khayashi4337.micradrone.build.plan.SlotResolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The wall-face contract that the opening and decoration generators rely on. Templates pass their OnSurface anchors
 * through the expander unchecked, so these checks are the first ones such an anchor meets.
 */
class GenContextTest {
    private static final PartTypeRegistry REGISTRY = CompileFixtures.REGISTRY;
    private static final int MAX_CELLS = 1_000;
    private static final String DOOR = "micra:door";
    private static final String FLOOR = "micra:floor";
    private static final String NORTH_WALL = "wall-n";
    private static final String EAST_WALL = "wall-e";
    /** The 5x5 test building: its walls are 5 cells long and 3 rows high (floor height 4 minus the floor row). */
    private static final int WALL_LENGTH = 5;
    private static final int WALL_HEIGHT = 3;

    /** A 5x5 one-floor building with a floor and four walls, generated into a fresh context. */
    private static GenContext generated(List<PlanNode> extra) {
        List<PlanNode> nodes = new ArrayList<>(shell(WALL_LENGTH, WALL_LENGTH, 1, WALL_HEIGHT + 1));
        nodes.add(node("f", FLOOR, "s", 0, 0, 0, Map.of()));
        nodes.addAll(extra);
        List<Issue> issues = new ArrayList<>();
        Map<String, PlanNode> byId = new HashMap<>();
        for (PlanNode n : nodes) {
            byId.put(n.id(), n);
        }
        Map<String, LocalPos> origins = Origins.resolve(nodes, SlotResolver.NONE, issues);
        GenContext ctx = new GenContext(REGISTRY, new Palette(REGISTRY.defaultPalette(), Map.of()), new Canvas(MAX_CELLS), issues,
                byId, origins);
        for (PlanNode n : nodes) {
            PartGenerators.Entry entry = PartGenerators.find(n.type()).orElse(null);
            if (entry != null) {
                PartType type = REGISTRY.get(n.type());
                entry.generator().generate(ctx, n, Params.resolve(type, n.params()));
            }
        }
        assertTrue(issues.isEmpty(), issues.toString());
        return ctx;
    }

    private static PlanNode door(String wall, Side side, int u, int v) {
        return onWall("d", DOOR, "s", wall, side, u, v, Map.of());
    }

    private static String refusal(GenContext ctx, PlanNode opener) {
        return assertThrows(GenAbort.class, () -> ctx.wallOfAnchor(opener)).issue().id();
    }

    @Test
    void anAnchorOnAValidWallGivesThatWall() {
        GenContext ctx = generated(List.of());
        WallInfo wall = ctx.wallOfAnchor(door(NORTH_WALL, Side.OUTER, WALL_LENGTH - 1, WALL_HEIGHT - 1));
        assertEquals(NORTH_WALL, wall.id());
        assertEquals(WALL_LENGTH, wall.length());
        assertEquals(WALL_HEIGHT, wall.height());
    }

    @Test
    void aNodeThatIsNotOnAWallFaceIsAnAnchorIssue() {
        GenContext ctx = generated(List.of());
        assertEquals("E-ANCHOR:d#anchor", refusal(ctx, node("d", DOOR, "s", 0, 0, 0, Map.of())));
    }

    @Test
    void aMissingOrNonWallTargetIsNoWall() {
        GenContext ctx = generated(List.of());
        assertEquals("E-OPENING-NO-WALL:d#anchor", refusal(ctx, door("ghost", Side.OUTER, 1, 0)));
        assertEquals("E-OPENING-NO-WALL:d#anchor", refusal(ctx, door("f", Side.OUTER, 1, 0)));
        assertEquals("E-OPENING-NO-WALL:d#anchor", refusal(ctx, door("s", Side.OUTER, 1, 0)));
    }

    @Test
    void aPositionOffTheWallIsNoWall() {
        GenContext ctx = generated(List.of());
        assertEquals("E-OPENING-NO-WALL:d#anchor", refusal(ctx, door(NORTH_WALL, Side.OUTER, WALL_LENGTH, 0)), "u past the end");
        assertEquals("E-OPENING-NO-WALL:d#anchor", refusal(ctx, door(NORTH_WALL, Side.OUTER, 0, WALL_HEIGHT)), "v above the top row");
        assertEquals("E-OPENING-NO-WALL:d#anchor", refusal(ctx, door(NORTH_WALL, Side.OUTER, -1, 0)), "negative u");
        assertEquals("E-OPENING-NO-WALL:d#anchor", refusal(ctx, door(NORTH_WALL, Side.OUTER, 0, -1)), "negative v");
    }

    @Test
    void aCoordinateAtTheIntLimitIsRefusedNotWrapped() {
        // the checks add the span in long arithmetic: MAX_VALUE + 1 must not wrap around and fit
        GenContext ctx = generated(List.of());
        assertEquals("E-OPENING-NO-WALL:d#anchor", refusal(ctx, door(NORTH_WALL, Side.OUTER, Integer.MAX_VALUE, 0)),
                "u at the int limit");
        assertEquals("E-OPENING-NO-WALL:d#anchor", refusal(ctx, door(NORTH_WALL, Side.OUTER, 0, Integer.MAX_VALUE)),
                "v at the int limit");
    }

    @Test
    void aFaceOtherThanOuterOrInnerIsAnAnchorIssue() {
        GenContext ctx = generated(List.of());
        assertEquals("E-ANCHOR:d#anchor", refusal(ctx, door(NORTH_WALL, Side.TOP, 1, 0)));
    }

    @Test
    void carvingRemovesTheWallsOwnCellsAndASharedCorner() {
        GenContext ctx = generated(List.of());
        WallInfo north = ctx.wallInfo(NORTH_WALL).orElseThrow();
        WallInfo east = ctx.wallInfo(EAST_WALL).orElseThrow();
        LocalPos own = north.cell(1, 0, 0);
        // the north-east corner column was generated by the north wall first; the east wall shares it
        LocalPos corner = east.cell(WALL_LENGTH - 1, 0, 0);
        assertEquals(NORTH_WALL, ctx.canvas().get(corner).ownerId());
        ctx.carve(door(NORTH_WALL, Side.OUTER, 1, 0), north, List.of(own));
        ctx.carve(onWall("d2", DOOR, "s", EAST_WALL, Side.OUTER, WALL_LENGTH - 1, 0, Map.of()), east, List.of(corner));
        assertNull(ctx.canvas().get(own));
        assertNull(ctx.canvas().get(corner));
    }

    @Test
    void carvingACellThatIsNotTheWallsIsRefusedAndChangesNothing() {
        GenContext ctx = generated(List.of());
        WallInfo north = ctx.wallInfo(NORTH_WALL).orElseThrow();
        LocalPos wallCell = north.cell(1, 0, 0);
        LocalPos floorCell = north.cell(1, 0, -1);
        assertEquals("f", ctx.canvas().get(floorCell).ownerId());
        GenAbort abort = assertThrows(GenAbort.class, () -> ctx.carve(door(NORTH_WALL, Side.OUTER, 1, 0), north,
                List.of(wallCell, floorCell)));
        assertEquals("E-OPENING-NO-WALL:d", abort.issue().id());
        assertEquals("1", abort.issue().data().get("count"));
        assertNotNull(ctx.canvas().get(wallCell), "a refused carve removes nothing");
        GenAbort outside = assertThrows(GenAbort.class, () -> ctx.carve(door(NORTH_WALL, Side.OUTER, 1, 0), north,
                List.of(north.cell(1, -1, 0))));
        assertEquals("E-OPENING-NO-WALL:d", outside.issue().id(), "an empty cell outside the wall");
    }

    @Test
    void carvingACellTwiceIsOneOverlapIssueNamingBothOpenings() {
        GenContext ctx = generated(List.of());
        WallInfo north = ctx.wallInfo(NORTH_WALL).orElseThrow();
        LocalPos cell = north.cell(2, 0, 1);
        ctx.carve(door(NORTH_WALL, Side.OUTER, 2, 0), north, List.of(cell));
        GenAbort again = assertThrows(GenAbort.class, () -> ctx.carve(onWall("w", "micra:window", "s", NORTH_WALL, Side.OUTER, 2, 1,
                Map.of()), north, List.of(cell)));
        // the refusal is the one report: both openings, how many cells and the first; the canvas has no second one for the pair
        assertEquals("E-OVERLAP:d,w#carve", again.issue().id());
        assertEquals(List.of("d", "w"), again.issue().subjects());
        assertEquals("1", again.issue().data().get(Canvas.DATA_COUNT));
        assertEquals(Canvas.posText(cell), again.issue().data().get(Canvas.DATA_FIRST_POS));
        assertEquals(List.of(), ctx.canvas().overlapIssues());
    }

    @Test
    void aCarveThatOverlapsSeveralCellsAndSeveralOpeningsIsStillOneIssue() {
        GenContext ctx = generated(List.of());
        WallInfo north = ctx.wallInfo(NORTH_WALL).orElseThrow();
        LocalPos first = north.cell(1, 0, 0);
        LocalPos second = north.cell(2, 0, 0);
        LocalPos free = north.cell(3, 0, 0);
        ctx.carve(door(NORTH_WALL, Side.OUTER, 1, 0), north, List.of(first));
        ctx.carve(onWall("c", DOOR, "s", NORTH_WALL, Side.OUTER, 2, 0, Map.of()), north, List.of(second));
        // w takes the cell of d (listed first), the cell of c and a free cell: two cells overlap, and two other openings own them
        GenAbort again = assertThrows(GenAbort.class, () -> ctx.carve(onWall("w", "micra:window", "s", NORTH_WALL, Side.OUTER, 1, 1,
                Map.of()), north, List.of(free, first, second)));
        assertEquals("E-OVERLAP:c,d,w#carve", again.issue().id());
        assertEquals("2", again.issue().data().get(Canvas.DATA_COUNT));
        assertEquals(Canvas.posText(first), again.issue().data().get(Canvas.DATA_FIRST_POS), "the first cell in the list that overlaps");
        assertNotNull(ctx.canvas().get(free), "a refused carve removes nothing");
    }

    @Test
    void aCellListedTwiceInOneCarveIsCountedAndRemovedOnce() {
        GenContext ctx = generated(List.of());
        WallInfo north = ctx.wallInfo(NORTH_WALL).orElseThrow();
        LocalPos wallCell = north.cell(1, 0, 0);
        LocalPos floorCell = north.cell(1, 0, -1);
        // the floor cell is listed twice but is one cell that is not the wall's
        GenAbort abort = assertThrows(GenAbort.class, () -> ctx.carve(door(NORTH_WALL, Side.OUTER, 1, 0), north,
                List.of(wallCell, floorCell, floorCell)));
        assertEquals("1", abort.issue().data().get(Canvas.DATA_COUNT));
        // a wall cell listed twice is one carve, one unit of work, and not an overlap with itself
        long before = ctx.canvas().attempts();
        ctx.carve(door(NORTH_WALL, Side.OUTER, 1, 0), north, List.of(wallCell, wallCell));
        assertNull(ctx.canvas().get(wallCell));
        assertEquals(1, ctx.canvas().attempts() - before);
    }

    @Test
    void everyCellACarveLooksAtCountsAsWork() {
        GenContext ctx = generated(List.of());
        WallInfo north = ctx.wallInfo(NORTH_WALL).orElseThrow();
        long before = ctx.canvas().attempts();
        ctx.carve(door(NORTH_WALL, Side.OUTER, 1, 0), north, List.of(north.cell(1, 0, 0), north.cell(1, 0, 1), north.cell(1, 0, 2)));
        assertEquals(3, ctx.canvas().attempts() - before);
        // a refused carve counts the cells it looked at too (the bad cell is the second of three)
        long beforeRefusal = ctx.canvas().attempts();
        assertThrows(GenAbort.class, () -> ctx.carve(door(NORTH_WALL, Side.OUTER, 2, 0), north,
                List.of(north.cell(2, 0, 0), north.cell(2, 0, -1), north.cell(2, 0, 1))));
        assertEquals(3, ctx.canvas().attempts() - beforeRefusal);
    }
}
