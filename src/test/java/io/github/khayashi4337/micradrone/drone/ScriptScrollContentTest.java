package io.github.khayashi4337.micradrone.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ScriptScrollContentTest {

    @Test
    void joinPagesConcatenatesWithNoSeparator() {
        assertEquals("move(\"north\")\ntill()", ScriptScrollContent.joinPages(List.of("move(\"north\")\n", "till()")));
    }

    @Test
    void joinPagesOfNoPagesIsEmpty() {
        assertEquals("", ScriptScrollContent.joinPages(List.of()));
    }

    @Test
    void isBlankTrueForNoPages() {
        assertTrue(ScriptScrollContent.isBlank(List.of()));
    }

    @Test
    void isBlankTrueForWhitespaceOnlyPages() {
        assertTrue(ScriptScrollContent.isBlank(List.of("  \n", "\t")));
    }

    @Test
    void isBlankFalseWhenAnyPageHasRealContent() {
        assertFalse(ScriptScrollContent.isBlank(List.of("  \n", "till()")));
    }

    @Test
    void splitIntoPagesOfEmptySourceIsNoPages() {
        assertEquals(List.of(), ScriptScrollContent.splitIntoPages("", 10));
    }

    @Test
    void splitIntoPagesChunksByMaxLength() {
        assertEquals(List.of("0123456789", "abcdefghij", "xy"), ScriptScrollContent.splitIntoPages("0123456789abcdefghijxy", 10));
    }

    @Test
    void splitThenJoinRoundTripsLosslessly() {
        String source = "# sample\nfor i in range(5):\n    move(\"north\")\n    till()\n    plant(\"wheat\")\n";
        List<String> pages = ScriptScrollContent.splitIntoPages(source, 16);
        assertEquals(source, ScriptScrollContent.joinPages(pages));
    }
}
