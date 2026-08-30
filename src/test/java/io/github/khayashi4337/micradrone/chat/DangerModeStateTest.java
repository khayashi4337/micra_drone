package io.github.khayashi4337.micradrone.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class DangerModeStateTest {

    @Test
    void defaultsToSafe() {
        assertFalse(new DangerModeState().isEnabled());
    }

    @Test
    void safeModeFlagsBlockToolsAndTheLocalClaudeMd() {
        List<String> flags = new DangerModeState().toCliFlags();
        assertTrue(flags.containsAll(List.of("--restricted", "--strict-mcp-config")));
        assertEquals(List.of("--setting-sources", "", "--restricted", "--strict-mcp-config", "--tools", ""), flags);
    }

    @Test
    void dangerousModeFlagsSkipPermissionsAndKeepThePlayersOwnSettings() {
        DangerModeState state = new DangerModeState();
        state.setEnabled(true);

        List<String> flags = state.toCliFlags();

        assertEquals(List.of("--dangerously-skip-permissions"), flags);
        assertFalse(flags.contains("--restricted"));
        assertFalse(flags.contains("--setting-sources"));
    }

    @Test
    void toggleFlipsBetweenTheTwoModes() {
        DangerModeState state = new DangerModeState();
        state.toggle();
        assertTrue(state.isEnabled());
        state.toggle();
        assertFalse(state.isEnabled());
    }
}
