package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.ai.PlanReplyExtractor;
import org.junit.jupiter.api.Test;

class PlanChunkCheckTest {
    @Test
    void aSingleChunkUploadIsAccepted() {
        assertTrue(PlanChunkCheck.check("t-1", 0, 1).isEmpty());
    }

    @Test
    void aChunkedUploadIsRefusedUntilTheAssemblerExists() {
        assertTrue(PlanChunkCheck.check("t-1", 0, 2).isPresent());
        assertTrue(PlanChunkCheck.check("t-1", 1, 1).isPresent());
    }

    @Test
    void theTransferIdIsBounded() {
        assertTrue(PlanChunkCheck.check("x".repeat(PlanChunkCheck.MAX_TRANSFER_ID_CHARS + 1), 0, 1).isPresent());
        assertTrue(PlanChunkCheck.check("x".repeat(PlanChunkCheck.MAX_TRANSFER_ID_CHARS), 0, 1).isEmpty());
        assertTrue(PlanChunkCheck.check("", 0, 1).isPresent());
    }

    @Test
    void theChunkLimitIsTheSameConstantM1AppliesToThePlanText() {
        assertEquals(PlanReplyExtractor.MAX_PLAN_CHARS, PlanChunkCheck.MAX_CHUNK_CHARS,
                "one source for the plan-text limit the wire and the extractor share");
        assertEquals(32_000, PlanChunkCheck.MAX_CHUNK_CHARS);
        assertEquals(64, PlanChunkCheck.MAX_TRANSFER_ID_CHARS);
    }
}
