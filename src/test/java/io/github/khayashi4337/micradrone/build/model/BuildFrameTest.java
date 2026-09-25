package io.github.khayashi4337.micradrone.build.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BuildFrameTest {
    @Test
    void northFrameIsIdentityWithZFlipped() {
        BuildFrame f = new BuildFrame(new IntPos(10, 64, 20), Facing.NORTH);
        // forward (+w) is Minecraft north (-z); right (+u) is east (+x)
        assertEquals(new IntPos(12, 67, 16), f.toWorld(new LocalPos(2, 3, 4)));
    }

    @Test
    void eastFrameForwardIsEastAndRightIsSouth() {
        BuildFrame f = new BuildFrame(new IntPos(0, 0, 0), Facing.EAST);
        assertEquals(new IntPos(1, 0, 0), f.toWorld(new LocalPos(0, 0, 1)));
        assertEquals(new IntPos(0, 0, 1), f.toWorld(new LocalPos(1, 0, 0)));
    }

    @Test
    void southAndWestFrames() {
        assertEquals(new IntPos(0, 0, 1), new BuildFrame(new IntPos(0, 0, 0), Facing.SOUTH).toWorld(new LocalPos(0, 0, 1)));
        assertEquals(new IntPos(-1, 0, 0), new BuildFrame(new IntPos(0, 0, 0), Facing.WEST).toWorld(new LocalPos(0, 0, 1)));
        // right of south-facing is west
        assertEquals(new IntPos(-1, 0, 0), new BuildFrame(new IntPos(0, 0, 0), Facing.SOUTH).toWorld(new LocalPos(1, 0, 0)));
    }

    @Test
    void toLocalInvertsToWorldForEveryFacing() {
        for (Facing facing : Facing.values()) {
            BuildFrame f = new BuildFrame(new IntPos(-7, 70, 33), facing);
            for (int u = -3; u <= 3; u++) {
                for (int v = -2; v <= 2; v++) {
                    for (int w = -3; w <= 3; w++) {
                        LocalPos p = new LocalPos(u, v, w);
                        assertEquals(p, f.toLocal(f.toWorld(p)), facing + " " + p);
                    }
                }
            }
        }
    }

    @Test
    void localFacingBecomesWorldFacingByTheFrameTurns() {
        BuildFrame east = new BuildFrame(new IntPos(0, 0, 0), Facing.EAST);
        assertEquals(Facing.EAST, east.toWorldFacing(Facing.NORTH));
        assertEquals(Facing.SOUTH, east.toWorldFacing(Facing.EAST));
        assertEquals(Facing.NORTH, east.toLocalFacing(Facing.EAST));
    }
}
