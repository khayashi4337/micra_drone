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
    void isValidIdAcceptsFileNamesAndScrollIds() {
        assertTrue(ScriptId.isValidId("main.mdrone"));
        assertTrue(ScriptId.isValidId("scroll:0:0"));
        assertFalse(ScriptId.isValidId(""));
        assertFalse(ScriptId.isValidId("../evil.mdrone"));
        assertFalse(ScriptId.isValidId("scroll:0"));
        assertFalse(ScriptId.isValidId("notes.txt"));
    }

    @Test
    void inventoryScrollIdRoundTrips() {
        String id = ScriptId.inventoryScrollId(9);
        assertEquals("inv:9", id);
        assertTrue(ScriptId.isInventoryScrollId(id));
        assertEquals(9, ScriptId.inventorySlot(id));
    }

    @Test
    void inventoryScrollIdRejectsNegativeSlot() {
        assertThrows(IllegalArgumentException.class, () -> ScriptId.inventoryScrollId(-1));
    }

    @Test
    void malformedInventoryScrollIdsAreNotInventoryScrollIds() {
        assertFalse(ScriptId.isInventoryScrollId(null));
        assertFalse(ScriptId.isInventoryScrollId("inv:"));
        assertFalse(ScriptId.isInventoryScrollId("inv:-1"));
        assertFalse(ScriptId.isInventoryScrollId("inv:a"));
        assertFalse(ScriptId.isInventoryScrollId("inv:1:2"));
        assertFalse(ScriptId.isInventoryScrollId("scroll:0:0"));
        assertEquals(-1, ScriptId.inventorySlot("inv:x"));
    }

    @Test
    void isValidIdAcceptsInventoryScrollIds() {
        assertTrue(ScriptId.isValidId("inv:0"));
        assertFalse(ScriptId.isValidId("inv:-1"));
    }

    @Test
    void theControllersOwnScriptIsAValidIdAndNothingElseIsMistakenForIt() {
        assertTrue(ScriptId.isControllerId(ScriptId.CONTROLLER_ID));
        assertTrue(ScriptId.isValidId(ScriptId.CONTROLLER_ID));
        assertFalse(ScriptId.isControllerId(""));
        assertFalse(ScriptId.isControllerId(null));
        assertFalse(ScriptId.isControllerId("controller.mdrone"));
        assertFalse(ScriptId.isScrollId(ScriptId.CONTROLLER_ID));
        assertFalse(ScriptId.isInventoryScrollId(ScriptId.CONTROLLER_ID));
    }
}
