package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import org.junit.jupiter.api.Test;

class SiteRelocationTest {
    @Test
    void onlyTheSiteMovesAndTheDesignStaysTheSame() {
        SemanticPlan hut = TestManifests.hutPlan();
        SemanticPlan moved = SiteRelocation.relocate(hut, "minecraft:overworld", new IntPos(5, -60, 7), Facing.EAST);
        assertEquals(new IntPos(5, -60, 7), moved.site().frame().origin());
        assertEquals(Facing.EAST, moved.site().frame().facing());
        assertEquals(hut.site().localBounds(), moved.site().localBounds());
        assertEquals("", moved.site().terrainDigest());
        assertEquals(hut.nodes(), moved.nodes());
    }
}
