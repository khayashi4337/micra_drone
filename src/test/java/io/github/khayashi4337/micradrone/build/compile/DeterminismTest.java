package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeterminismTest {
    /** How often the same plan is compiled; every run must produce the identical manifest hash. */
    private static final int COMPILE_RUNS = 1000;

    @Test
    void theSameInputGivesTheSameHashOneThousandTimes() throws IOException {
        var plan = GoldenHutTest.hut();
        String first = CompileFixtures.compile(plan).manifest().hash();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < COMPILE_RUNS; i++) {
            seen.add(CompileFixtures.compile(plan).manifest().hash());
        }
        assertEquals(Set.of(first), seen);
    }
}
