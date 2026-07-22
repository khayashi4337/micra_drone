package io.github.khayashi4337.micradrone.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScriptIdTest {

    @Test
    void scrollIdRoundTrips() {
        String id = ScriptId.scrollId(2, 13);
        assertEquals("scroll:2:13", id);
        assertTrue(ScriptId.isScrollId(id));
        assertEquals(2, ScriptId.scrollChestIndex(id));
        assertEquals(13, ScriptId.scrollSlot(id));
    }

    @Test
    void scrollIdRejectsNegativeParts() {
        assertThrows(IllegalArgumentException.class, () -> ScriptId.scrollId(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> ScriptId.scrollId(0, -1));
    }

    @Test
    void malformedScrollIdsAreNotScrollIds() {
        assertFalse(ScriptId.isScrollId(null));
        assertFalse(ScriptId.isScrollId("scroll:"));
        assertFalse(ScriptId.isScrollId("scroll:1"));
        assertFalse(ScriptId.isScrollId("scroll:1:2:3"));
        assertFalse(ScriptId.isScrollId("scroll:a:b"));
        assertFalse(ScriptId.isScrollId("scroll:-1:2"));
        assertFalse(ScriptId.isScrollId("scroll: 1:2"));
        assertFalse(ScriptId.isScrollId("scroll:1:2 "));
        assertFalse(ScriptId.isScrollId("main.mdrone"));
    }

    @Test
    void malformedScrollIdsReportMinusOneIndexes() {
        assertEquals(-1, ScriptId.scrollChestIndex("scroll:x:1"));
        assertEquals(-1, ScriptId.scrollSlot("main.mdrone"));
    }

    @Test
    void isValidIdAcceptsBothFileNamesAndScrollIds() {
        assertTrue(ScriptId.isValidId("main.mdrone"));
        assertTrue(ScriptId.isValidId("scroll:0:0"));
        assertFalse(ScriptId.isValidId("../evil.mdrone"));
        assertFalse(ScriptId.isValidId("scroll:0"));
        assertFalse(ScriptId.isValidId("notes.txt"));
    }
}
