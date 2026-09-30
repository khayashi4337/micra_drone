package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.ai.PlanReplyExtractor;
import java.util.Optional;

/**
 * The envelope checks of one uploaded plan chunk (M2): the MVP serves only single-chunk uploads
 * ({@code index == 0 && count == 1}); multi-chunk assembly is a later task's work. The checks are
 * pure so they are unit-testable without a Minecraft runtime and so the network adapter does not
 * re-derive them. Refusals are technical one-liners: they feed a fix request back to the client's
 * AI, they are not child-facing lines.
 */
public final class PlanChunkCheck {
    /** The plan-text cap shared with M1's reply extractor - one source for one limit (32 KiB). */
    public static final int MAX_CHUNK_CHARS = PlanReplyExtractor.MAX_PLAN_CHARS;
    /** A transfer id groups the chunks of one upload; bounded so the grouping key stays small. */
    public static final int MAX_TRANSFER_ID_CHARS = 64;

    private PlanChunkCheck() {
    }

    /** Empty when the chunk's envelope is servable; otherwise the technical refusal text. */
    public static Optional<String> check(String transferId, int index, int count) {
        if (transferId == null || transferId.isEmpty() || transferId.length() > MAX_TRANSFER_ID_CHARS) {
            return Optional.of("transferId must be 1.." + MAX_TRANSFER_ID_CHARS + " chars");
        }
        if (index != 0 || count != 1) {
            return Optional.of("chunked upload unsupported: index=" + index + " count=" + count
                    + " (only index=0 count=1 is served)");
        }
        return Optional.empty();
    }
}
