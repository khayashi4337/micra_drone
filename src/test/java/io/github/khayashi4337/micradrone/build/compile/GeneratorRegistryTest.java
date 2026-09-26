package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.gen.PartGenerators;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.PartType;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class GeneratorRegistryTest {
    /** The building parts the registry offers to plans (design doc 05, section 1.1.1). */
    private static final int EXPECTED_PART_COUNT = 22;

    @Test
    void everyBuildingPartHasAGeneratorAndNothingElseDoes() {
        Set<String> parts = new TreeSet<>();
        for (PartType t : BuildingParts.registry().userParts()) {
            parts.add(t.id());
        }
        assertEquals(parts, new TreeSet<>(PartGenerators.ids()));
        assertEquals(EXPECTED_PART_COUNT, parts.size());
    }

    @Test
    void rotationUnsupportedPartsAreRealParts() {
        for (String id : BuildingParts.ROTATION_UNSUPPORTED) {
            assertTrue(BuildingParts.registry().contains(id), id);
        }
    }
}
