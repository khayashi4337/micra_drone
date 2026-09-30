package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PalettePolicyTest {
    @Test
    void anEmptyTagForbidsEverythingAndOnlyAnAbsentTagFallsBackToTheShippedTable() {
        assertTrue(PalettePolicy.from(Optional.of(Set.of())).allowed().isEmpty(), "an empty tag is a policy, not a missing one");
        assertEquals(Set.of("minecraft:stone"), PalettePolicy.from(Optional.of(Set.of("minecraft:stone"))).allowed());
        assertEquals(PlaceableBlockPolicy.builtin().allowed(), PalettePolicy.from(Optional.empty()).allowed());
    }
}
