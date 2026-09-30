package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.LogisticsPlan;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Site;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

class OperatingBoxTest {
    @Test
    void theAirAboveADockIsPartOfTheClaim() {
        PlacementManifest m = TestManifests.smallHut();
        Site site = new Site(TestManifests.DIM, new BuildFrame(new IntPos(0, 64, 0), Facing.NORTH), new Box(-2, -9, -4, 4, 6, 2),
                "", "");
        LogisticsPlan.Dock dock = new LogisticsPlan.Dock("pad", new Box(0, 0, 0, 2, 0, 2), new Box(0, 1, 0, 2, 30, 2),
                Facing.NORTH, List.of(), List.of());
        SemanticPlan plan = new SemanticPlan(SemanticPlan.SCHEMA_VERSION, "p", 1, null, site, StyleSpec.EMPTY, List.of(), List.of(),
                new LogisticsPlan(List.of(dock), List.of(), List.of()), null);
        Box op = OperatingBox.of(m, plan);
        assertEquals(64 + 30, op.maxB(), "the clearance box reaches 30 blocks above the site origin");
        assertEquals(m.worldBounds().minB(), op.minB());
        assertEquals(m.worldBounds(), OperatingBox.of(m, SemanticPlan.empty("p")), "no logistics: the building's box");
    }
}
