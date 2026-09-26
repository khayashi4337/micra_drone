package io.github.khayashi4337.micradrone.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanValueTextTest {
    @Test
    void numbersAndBooleansRenderTheWayPrintShowsThem() {
        assertEquals("3", PlanValueText.describe(3.0));
        assertEquals("1.5", PlanValueText.describe(1.5));
        assertEquals("True", PlanValueText.describe(true));
        assertEquals("False", PlanValueText.describe(false));
    }

    @Test
    void aStringIsCutAtSixtyCharacters() {
        String sixty = "x".repeat(60);
        assertEquals(sixty, PlanValueText.describe(sixty));
        assertEquals("x".repeat(60) + "...", PlanValueText.describe("x".repeat(61)));
        assertEquals("x".repeat(60) + "...", PlanValueText.describe("x".repeat(500)));
    }

    @Test
    void collectionsAndOtherValuesBecomeJustTheirTypeName() {
        assertEquals("list", PlanValueText.describe(List.of(1.0)));
        assertEquals("set", PlanValueText.describe(Set.of(1.0)));
        assertEquals("dict", PlanValueText.describe(Map.of("a", 1.0)));
        assertEquals("function", PlanValueText.describe(new MicraFunction("f", List.of(), List.of())));
        assertEquals("semaphore", PlanValueText.describe(new MicraSemaphore(0)));
        assertEquals("None", PlanValueText.describe(MicraNone.INSTANCE));
    }
}
