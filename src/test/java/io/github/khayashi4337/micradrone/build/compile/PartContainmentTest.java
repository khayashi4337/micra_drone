package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.Site;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import io.github.khayashi4337.micradrone.build.compile.gen.PartGenerators;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.PartParams;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The freestanding parts keep every cell they emit inside the box their parameters hand to them. The box is
 * not read back from the generator: each part's bounds and cell count are derived here from the parameters
 * alone (design doc 01, the GENERATED volume rule: a generated part is checked against numbers derived by
 * hand, not against a copy of its generator).
 */
class PartContainmentTest {
    private static final IntPos ORIGIN = new IntPos(1000, 64, -2000);
    /** Local bounds for the random parts: u in [-50, 6000], v and w within +/-50. */
    private static final Box WIDE = new Box(-50, -50, -50, 6000, 200, 50);
    /** A part compiled alone stands here: far enough out that a mirror cannot push it against the bounds edge. */
    private static final LocalPos LONE_ANCHOR = new LocalPos(200, 0, 0);
    private static final int QUARTER_TURNS = 4;
    private static final boolean[] MIRROR = {false, true};
    private static final long FIRST_SEED = 1;
    private static final long LAST_SEED = 20;
    /** The chimney cap reaches this far past the shaft on every side. */
    private static final int CAP_OVERHANG = 1;
    /** A chimney of this size is hollow, so its shaft count is a ring. */
    private static final int FLUE_SIZE = 3;
    /** A ramp's slabs pair up: two cells make one block of climb. */
    private static final int RAMP_CELLS_PER_BLOCK = 2;
    private static final String LAMP_POST = "post";

    /** The (u, v, w) box a part must stay in, derived from its parameters before any rot. */
    private static Box localBox(PlanNode n) {
        return switch (n.type()) {
            case "micra:pillar", "micra:ladder" -> new Box(0, 0, 0, 0, i(n, PartParams.HEIGHT) - 1, 0);
            case "micra:beam" -> beamBox(i(n, PartParams.LENGTH), s(n, PartParams.AXIS));
            case "micra:chimney" -> chimneyBox(i(n, PartParams.HEIGHT), i(n, PartParams.SIZE), b(n, PartParams.CAP));
            case "micra:stairs" -> runBox(s(n, PartParams.DIR), i(n, PartParams.STEPS), i(n, PartParams.WIDTH),
                    i(n, PartParams.STEPS) - 1);
            case "micra:catwalk" -> runBox(s(n, PartParams.DIR), i(n, PartParams.LENGTH), i(n, PartParams.WIDTH),
                    b(n, PartParams.RAIL) ? 1 : 0);
            case "micra:railing" -> runBox(s(n, PartParams.DIR), i(n, PartParams.LENGTH), 1,
                    i(n, PartParams.HEIGHT) - 1);
            case "micra:ramp" -> runBox(s(n, PartParams.DIR), i(n, PartParams.LENGTH), i(n, PartParams.WIDTH),
                    (i(n, PartParams.LENGTH) - 1) / RAMP_CELLS_PER_BLOCK);
            case "micra:lamp" -> new Box(0, 0, 0, 0,
                    s(n, PartParams.KIND).equals(LAMP_POST) ? i(n, PartParams.HEIGHT) : 0, 0);
            case "micra:road" -> runBox(s(n, PartParams.DIR), i(n, PartParams.LENGTH), i(n, PartParams.WIDTH), 0);
            case "micra:dock_pad" -> new Box(0, 0, 0, i(n, PartParams.WIDTH) - 1, 1, i(n, PartParams.DEPTH) - 1);
            default -> throw new IllegalArgumentException("no derived box for " + n.type());
        };
    }

    /** The beam lies along its axis: {@code length} cells on it, one cell on the other two. */
    private static Box beamBox(int length, String axis) {
        return switch (axis) {
            case PartParams.AXIS_U -> new Box(0, 0, 0, length - 1, 0, 0);
            case PartParams.AXIS_V -> new Box(0, 0, 0, 0, length - 1, 0);
            default -> new Box(0, 0, 0, 0, 0, length - 1);
        };
    }

    /** The shaft is {@code size} by {@code size} by {@code height}; a cap is a slab ring one wider on every side. */
    private static Box chimneyBox(int height, int size, boolean cap) {
        int reach = cap ? size + CAP_OVERHANG - 1 : size - 1;
        int low = cap ? -CAP_OVERHANG : 0;
        return new Box(low, 0, low, reach, cap ? height : height - 1, reach);
    }

    /**
     * A run's footprint: {@code ahead} cells along the direction and {@code right} cells to its right hand. The
     * extreme u and w are reached at the four footprint corners (the offsets are linear in ahead and right).
     */
    private static Box runBox(String dir, int ahead, int right, int height) {
        Facing heading = Facing.parse(dir);
        Facing hand = heading.rotate(1);
        int uLo = Integer.MAX_VALUE;
        int uHi = Integer.MIN_VALUE;
        int wLo = Integer.MAX_VALUE;
        int wHi = Integer.MIN_VALUE;
        for (int a : new int[] {0, ahead - 1}) {
            for (int j : new int[] {0, right - 1}) {
                int u = a * heading.du() + j * hand.du();
                int w = a * heading.dw() + j * hand.dw();
                uLo = Math.min(uLo, u);
                uHi = Math.max(uHi, u);
                wLo = Math.min(wLo, w);
                wHi = Math.max(wHi, w);
            }
        }
        return new Box(uLo, 0, wLo, uHi, height, wHi);
    }

    /** The cell count the parameters promise: containment alone would not notice a part that emits too few. */
    private static int cellCount(PlanNode n) {
        return switch (n.type()) {
            case "micra:pillar", "micra:ladder" -> i(n, PartParams.HEIGHT);
            case "micra:beam" -> i(n, PartParams.LENGTH);
            case "micra:chimney" -> chimneyCells(i(n, PartParams.HEIGHT), i(n, PartParams.SIZE), b(n, PartParams.CAP));
            case "micra:stairs" -> i(n, PartParams.STEPS) * i(n, PartParams.WIDTH);
            case "micra:catwalk" -> catwalkCells(i(n, PartParams.LENGTH), i(n, PartParams.WIDTH), b(n, PartParams.RAIL));
            case "micra:railing" -> i(n, PartParams.LENGTH) * i(n, PartParams.HEIGHT);
            case "micra:ramp", "micra:road" -> i(n, PartParams.LENGTH) * i(n, PartParams.WIDTH);
            case "micra:lamp" -> s(n, PartParams.KIND).equals(LAMP_POST) ? i(n, PartParams.HEIGHT) + 1 : 1;
            case "micra:dock_pad" -> i(n, PartParams.WIDTH) * i(n, PartParams.DEPTH) + 1;
            default -> throw new IllegalArgumentException("no derived count for " + n.type());
        };
    }

    private static int chimneyCells(int height, int size, boolean cap) {
        int shaft = size == FLUE_SIZE ? size * size - 1 : size * size;
        int capCells = (size + 2 * CAP_OVERHANG) * (size + 2 * CAP_OVERHANG);
        return height * shaft + (cap ? capCells : 0);
    }

    /** A one-wide walkway has one column that is both outer columns, so it carries one fence per row. */
    private static int catwalkCells(int length, int width, boolean rail) {
        return length * width + (rail ? length * Math.min(width, 2) : 0);
    }

    private static int i(PlanNode n, String name) {
        return ((ParamValue.IntV) n.params().get(name)).value();
    }

    private static boolean b(PlanNode n, String name) {
        return ((ParamValue.BoolV) n.params().get(name)).value();
    }

    private static String s(PlanNode n, String name) {
        return ((ParamValue.StrV) n.params().get(name)).value();
    }

    /**
     * A turn or a mirror only swaps and flips axes, so the images of a box's two opposite corners bound the
     * whole image. {@code placed} is the same origin + rot(offset) the compile gives the node's cells.
     */
    private static Box imageBox(Box local, Rot rot) {
        LocalPos lo = rot.apply(new LocalPos(local.minA(), local.minB(), local.minC()));
        LocalPos hi = rot.apply(new LocalPos(local.maxA(), local.maxB(), local.maxC()));
        return Box.of(lo.u() + LONE_ANCHOR.u(), lo.v() + LONE_ANCHOR.v(), lo.w() + LONE_ANCHOR.w(),
                hi.u() + LONE_ANCHOR.u(), hi.v() + LONE_ANCHOR.v(), hi.w() + LONE_ANCHOR.w());
    }

    private static PlacementManifest compileAlone(PlanNode n, Rot rot) {
        PlanNode lone = new PlanNode(n.id(), n.type(), n.parent(),
                new Anchor.Absolute(LONE_ANCHOR, rot), n.params(), n.tags(), n.label());
        Site site = new Site("minecraft:overworld", new BuildFrame(ORIGIN, Facing.NORTH), WIDE, "", "");
        CompileResult r = CompileFixtures.compile(CompileFixtures.plan(site, StyleSpec.EMPTY, List.of(lone)));
        assertTrue(r.issues().isEmpty(), n.type() + " " + rot + ": " + r.issues());
        return r.manifest();
    }

    /**
     * Every freestanding part compiles with no parent, and every cell it places lies inside the box derived
     * from its parameters — under all eight turns-and-mirrors, on every seed {@link RandomParts} draws.
     */
    @Test
    void everyFreestandingPartStaysInsideTheBoxItsParametersDerive() {
        for (long seed = FIRST_SEED; seed <= LAST_SEED; seed++) {
            for (PlanNode n : RandomParts.nodes(seed)) {
                assertNull(n.parent(), n.type() + " must stand alone");
                for (int turns = 0; turns < QUARTER_TURNS; turns++) {
                    for (boolean mirror : MIRROR) {
                        Rot rot = new Rot(turns, mirror);
                        PlacementManifest m = compileAlone(n, rot);
                        Box box = imageBox(localBox(n), rot);
                        int count = 0;
                        for (Placement p : m.placements()) {
                            assertEquals(n.id(), p.partNodeId());
                            LocalPos pos = m.frame().toLocal(p.pos());
                            assertTrue(box.contains(pos.u(), pos.v(), pos.w()),
                                    n.type() + " " + rot + " seed " + seed + ": cell " + pos + " outside " + box);
                            count++;
                        }
                        assertEquals(cellCount(n), count, n.type() + " " + rot + " seed " + seed);
                    }
                }
            }
        }
    }

    /**
     * The parts that stand alone are exactly the parts that take a rotation: the eleven in
     * {@link RandomParts#FREESTANDING} are the registry minus {@link BuildingParts#ROTATION_UNSUPPORTED}.
     * A new rotatable part must be added to {@link #localBox} and {@link #cellCount}, or it fails to compile.
     */
    @Test
    void theFreestandingRosterIsExactlyTheRotatableParts() {
        Set<String> want = new TreeSet<>(PartGenerators.ids());
        want.removeAll(BuildingParts.ROTATION_UNSUPPORTED);
        assertEquals(want, new TreeSet<>(RandomParts.FREESTANDING));
    }
}
