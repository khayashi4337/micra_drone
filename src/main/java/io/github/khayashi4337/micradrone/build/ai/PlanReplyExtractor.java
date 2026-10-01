package io.github.khayashi4337.micradrone.build.ai;

import io.github.khayashi4337.micradrone.chat.CodeBlockParser;
import io.github.khayashi4337.micradrone.chat.CodeBlockParser.LocatedBlock;
import java.util.List;
import java.util.Objects;

/**
 * Pulls the plan JSON out of the AI's reply (P4 task M1): a short hiragana line for the child, then a
 * fenced {@code ```json} block. When the reply holds several {@code json} blocks the LAST one wins -
 * the model may have corrected itself mid-reply. With no {@code json} block at all, the first fenced
 * block is the fallback. The JSON itself is not parsed here; the server's PlanFileReader validates it.
 */
public final class PlanReplyExtractor {
    /**
     * Largest plan body accepted, in chars: the C2S upload channel caps a packet at 32 KiB, so the
     * extractor rejects earlier instead of letting an oversized plan reach the wire.
     */
    public static final int MAX_PLAN_CHARS = 32_000;

    private PlanReplyExtractor() {
    }

    /**
     * One extraction: on success {@code error} is null; on failure {@code json} and
     * {@code childSays} are null and {@code error} carries a one-line reason.
     */
    public record Extracted(String json, String childSays, String error) {
    }

    public static Extracted extract(String reply) {
        Objects.requireNonNull(reply, "reply");
        List<LocatedBlock> blocks = CodeBlockParser.parseLocated(reply);
        if (blocks.isEmpty()) {
            return new Extracted(null, null, "no json block");
        }
        LocatedBlock chosen = null;
        for (LocatedBlock b : blocks) {
            if (b.language().equalsIgnoreCase("json")) {
                chosen = b;
            }
        }
        if (chosen == null) {
            chosen = blocks.get(0);
        }
        String json = chosen.code().strip();
        if (json.isEmpty()) {
            return new Extracted(null, null, "empty json block");
        }
        if (json.length() > MAX_PLAN_CHARS) {
            return new Extracted(null, null, "json over " + MAX_PLAN_CHARS + " chars");
        }
        if (!json.startsWith("{") || !json.endsWith("}")) {
            return new Extracted(null, null, "not a json object");
        }
        String childSays = reply.substring(0, chosen.fenceStart()).strip();
        return new Extracted(json, childSays, null);
    }
}
