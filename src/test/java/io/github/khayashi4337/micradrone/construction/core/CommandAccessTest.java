package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommandAccessTest {
    private static final UUID OWNER = new UUID(0, 1);
    private static final UUID STRANGER = new UUID(0, 2);

    @Test
    void onlyTheOwnerOrAnOperatorMayInspectAJob() {
        assertTrue(CommandAccess.mayInspect(OWNER, false, OWNER), "the owner sees the job");
        assertTrue(CommandAccess.mayInspect(STRANGER, true, OWNER), "an operator sees the job");
        assertFalse(CommandAccess.mayInspect(STRANGER, false, OWNER),
                "anyone else gets the not-found answer, never learning the job exists");
        assertFalse(CommandAccess.mayInspect(null, false, OWNER), "a viewer with no player is not the owner");
    }
}
