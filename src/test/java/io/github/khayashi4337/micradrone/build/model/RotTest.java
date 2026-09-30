package io.github.khayashi4337.micradrone.build.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class RotTest {
    private static final List<LocalPos> SAMPLES = List.of(
            new LocalPos(0, 0, 0), new LocalPos(1, 2, 3), new LocalPos(-4, 1, 5), new LocalPos(3, -2, -7));

    @Test
    void oneQuarterTurnMapsNorthToEast() {
        // rotation of (u,w) is (w,-u): NORTH (0,+1) -> EAST (+1,0)
        assertEquals(new LocalPos(1, 0, 0), new Rot(1, false).apply(new LocalPos(0, 0, 1)));
        assertEquals(new LocalPos(0, 0, -1), new Rot(1, false).apply(new LocalPos(1, 0, 0)));
        assertEquals(Facing.EAST, new Rot(1, false).apply(Facing.NORTH));
    }

    @Test
    void mirrorFlipsUAndSwapsEastWest() {
        assertEquals(new LocalPos(-3, 2, 4), new Rot(0, true).apply(new LocalPos(3, 2, 4)));
        assertEquals(Facing.WEST, new Rot(0, true).apply(Facing.EAST));
        assertEquals(Facing.NORTH, new Rot(0, true).apply(Facing.NORTH));
    }

    @Test
    void quarterTurnsAreNormalized() {
        assertEquals(new Rot(0, false), new Rot(4, false));
        assertEquals(new Rot(3, true), new Rot(-1, true));
    }

    @Test
    void composeMeansInnerFirstThenOuter() {
        for (int qo = 0; qo < 4; qo++) {
            for (int qi = 0; qi < 4; qi++) {
                for (boolean mo : new boolean[]{false, true}) {
                    for (boolean mi : new boolean[]{false, true}) {
                        Rot outer = new Rot(qo, mo);
                        Rot inner = new Rot(qi, mi);
                        Rot composed = Rot.compose(outer, inner);
                        for (LocalPos p : SAMPLES) {
                            assertEquals(outer.apply(inner.apply(p)), composed.apply(p),
                                    "compose " + outer + " after " + inner + " on " + p);
                        }
                        for (Facing f : Facing.values()) {
                            assertEquals(outer.apply(inner.apply(f)), composed.apply(f));
                        }
                    }
                }
            }
        }
    }
}
