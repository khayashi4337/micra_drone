package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class ApproveArgsTest {
    @Test
    void flagsAndAcceptedRisksAreParsed() {
        ApproveArgs.Parsed p = ApproveArgs.parse("confirm-terraform accept=W-UNMODELED:press,W-STRESS-MARGIN:net");
        assertNull(p.error());
        assertEquals(new Confirmations(true, false), p.confirmations());
        assertEquals(List.of(new AcceptedRisk("W-UNMODELED:press", ""), new AcceptedRisk("W-STRESS-MARGIN:net", "")), p.risks());
        assertEquals(Confirmations.NONE, ApproveArgs.parse("").confirmations());
        assertNotNull(ApproveArgs.parse("confirm-everything").error());
    }
}
