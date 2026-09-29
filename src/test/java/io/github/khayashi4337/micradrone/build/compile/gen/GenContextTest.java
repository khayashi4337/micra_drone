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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The wall-face contract that the opening and decoration generators rely on. Templates pass their OnSurface anchors
 * through the expander unchecked, so these checks are the first ones such an anchor meets. The opening checks below
 * register claims and have the resolver decide them all at once.
 */
class GenContextTest {
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
        return CompileFixtures.generate(nodes, Map.of());
    }

    private static PlanNode door(String wall, Side side, int u, int v) {
        return onWall("d", DOOR, "s", wall, side, u, v, Map.of());
    }

    private static String refusal(GenContext ctx, PlanNode opener) {
        return assertThrows(GenAbort.class, () -> ctx.wallOfAnchor(opener)).issue().id();
    }

    /**
     * Registers a passage claim on the wall with the given tunnel cells; the emitter places nothing, so the
     * resolver's own checks are what the test exercises. The claims are decided when {@link GenContext#resolveOpenings}
     * runs.
     */
    private static void claim(GenContext ctx, PlanNode node, WallInfo wall, List<LocalPos> tunnel) {
        ctx.claimOpening(new OpeningResolver.Claim(node, new OpeningSpot(wall, 0, 0, true, 0, 1, 1), tunnel,
                OpeningResolver.Contract.PASSAGE, Map.of(), c -> {
                }));
    }

    private static List<String> issueIds(GenContext ctx) {
        return ctx.issues().stream().map(Issue::id).toList();
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
        // one claim whose tunnel is a wall cell plus the shared corner cell: the corner is the opening's to take too
        claim(ctx, door(NORTH_WALL, Side.OUTER, 1, 0), north, List.of(own, corner));
        ctx.resolveOpenings();
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
        claim(ctx, door(NORTH_WALL, Side.OUTER, 1, 0), north, List.of(wallCell, floorCell));
        ctx.resolveOpenings();
        Issue issue = ctx.issues().stream().filter(i -> i.id().equals("E-OPENING-NO-WALL:d")).findFirst().orElseThrow();
        assertEquals("1", issue.data().get("count"));
        assertNotNull(ctx.canvas().get(wallCell), "a refused opening carves nothing");
        // an empty cell outside the wall is not the wall's either
        GenContext outside = generated(List.of());
        claim(outside, door(NORTH_WALL, Side.OUTER, 1, 0), outside.wallInfo(NORTH_WALL).orElseThrow(),
                List.of(north.cell(1, -1, 0)));
        outside.resolveOpenings();
        assertEquals(List.of("E-OPENING-NO-WALL:d"), issueIds(outside));
    }

    @Test
    void carvingACellTwiceIsOneOverlapIssueNamingBothOpenings() {
        GenContext ctx = generated(List.of());
        WallInfo north = ctx.wallInfo(NORTH_WALL).orElseThrow();
        LocalPos cell = north.cell(2, 0, 1);
        claim(ctx, door(NORTH_WALL, Side.OUTER, 2, 0), north, List.of(cell));
        claim(ctx, onWall("w", "micra:window", "s", NORTH_WALL, Side.OUTER, 2, 1, Map.of()), north, List.of(cell));
        ctx.resolveOpenings();
        // the one report of the overlap names both openings, the number of cells and the first one
        assertEquals(List.of("E-OVERLAP:d,w#carve"), issueIds(ctx));
        Issue issue = ctx.issues().get(0);
        assertEquals(List.of("d", "w"), issue.subjects());
        assertEquals("1", issue.data().get(Canvas.DATA_COUNT));
        assertEquals(Canvas.posText(cell), issue.data().get(Canvas.DATA_FIRST_POS));
        assertNotNull(ctx.canvas().get(cell), "a refused opening carves nothing");
        assertEquals(List.of(), ctx.canvas().overlapIssues());
    }

    @Test
    void aCarveThatOverlapsSeveralCellsAndSeveralOpeningsIsStillOneIssue() {
        GenContext ctx = generated(List.of());
        WallInfo north = ctx.wallInfo(NORTH_WALL).orElseThrow();
        LocalPos first = north.cell(1, 0, 0);
        LocalPos second = north.cell(2, 0, 0);
        LocalPos free = north.cell(3, 0, 0);
        claim(ctx, door(NORTH_WALL, Side.OUTER, 1, 0), north, List.of(first));
        claim(ctx, onWall("c", DOOR, "s", NORTH_WALL, Side.OUTER, 2, 0, Map.of()), north, List.of(second));
        // w takes the cell of d, the cell of c and a free cell: two cells overlap, and two other openings own them
        claim(ctx, onWall("w", "micra:window", "s", NORTH_WALL, Side.OUTER, 1, 1, Map.of()), north,
                List.of(free, first, second));
        ctx.resolveOpenings();
        assertEquals(List.of("E-OVERLAP:c,d,w#carve"), issueIds(ctx));
        Issue issue = ctx.issues().get(0);
        assertEquals("2", issue.data().get(Canvas.DATA_COUNT));
        assertEquals(Canvas.posText(first), issue.data().get(Canvas.DATA_FIRST_POS), "the lowest overlapping cell");
        assertNotNull(ctx.canvas().get(free), "a refused opening carves nothing");
    }

    @Test
    void aCellListedTwiceInOneCarveIsCountedAndRemovedOnce() {
        // the floor cell is listed twice but is one cell that is not the wall's
        GenContext ctx = generated(List.of());
        WallInfo north = ctx.wallInfo(NORTH_WALL).orElseThrow();
        LocalPos wallCell = north.cell(1, 0, 0);
        LocalPos floorCell = north.cell(1, 0, -1);
        claim(ctx, door(NORTH_WALL, Side.OUTER, 1, 0), north, List.of(wallCell, floorCell, floorCell));
        ctx.resolveOpenings();
        Issue issue = ctx.issues().stream().filter(i -> i.id().equals("E-OPENING-NO-WALL:d")).findFirst().orElseThrow();
        assertEquals("1", issue.data().get(Canvas.DATA_COUNT));
        // a wall cell listed twice is one carve, one unit of work, and not an overlap with itself
        GenContext again = generated(List.of());
        WallInfo northAgain = again.wallInfo(NORTH_WALL).orElseThrow();
        LocalPos cell = northAgain.cell(1, 0, 0);
        long before = again.canvas().attempts();
        claim(again, door(NORTH_WALL, Side.OUTER, 1, 0), northAgain, List.of(cell, cell));
        again.resolveOpenings();
        assertNull(again.canvas().get(cell));
        assertEquals(1, again.canvas().attempts() - before);
    }

    @Test
    void everyCellACarveLooksAtCountsAsWork() {
        GenContext ctx = generated(List.of());
        WallInfo north = ctx.wallInfo(NORTH_WALL).orElseThrow();
        long before = ctx.canvas().attempts();
        claim(ctx, door(NORTH_WALL, Side.OUTER, 1, 0), north,
                List.of(north.cell(1, 0, 0), north.cell(1, 0, 1), north.cell(1, 0, 2)));
        ctx.resolveOpenings();
        assertEquals(3, ctx.canvas().attempts() - before);
        // a refused claim counts the cells it looked at too (the bad cell is the second of three)
        GenContext refused = generated(List.of());
        WallInfo northRefused = refused.wallInfo(NORTH_WALL).orElseThrow();
        long beforeRefusal = refused.canvas().attempts();
        claim(refused, door(NORTH_WALL, Side.OUTER, 2, 0), northRefused,
                List.of(northRefused.cell(2, 0, 0), northRefused.cell(2, 0, -1), northRefused.cell(2, 0, 1)));
        refused.resolveOpenings();
        assertEquals(3, refused.canvas().attempts() - beforeRefusal);
        assertTrue(issueIds(refused).contains("E-OPENING-NO-WALL:d"));
    }
}
