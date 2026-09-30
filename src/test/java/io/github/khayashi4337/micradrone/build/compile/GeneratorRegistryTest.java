package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.compile.gen.PartGenerators;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.PartType;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class GeneratorRegistryTest {
    /** The building parts the registry offers to plans (design doc 05, section 1.1.1). */
    private static final int EXPECTED_PART_COUNT = 22;

    @Test
    void everyBuildingPartHasAGeneratorAndNothingElseDoes() {
        Set<String> parts = BuildingParts.registry().userParts().stream()
                .map(PartType::id)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(parts, new TreeSet<>(PartGenerators.ids()));
        assertEquals(EXPECTED_PART_COUNT, parts.size());
    }
}
