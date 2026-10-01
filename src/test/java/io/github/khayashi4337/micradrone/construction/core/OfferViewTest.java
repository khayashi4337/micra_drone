package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OfferViewTest {
    private static final UUID OWNER = new UUID(0, 7);

    private static SubmitOutcome offered(ReplacementSummary replacements, long etaTicks, List<Issue> issues) {
        PendingApproval pending = new PendingApproval("0123abcd", "minecraft:overworld", OWNER, 12_345L, issues,
                "survey-digest");
        return SubmitOutcome.offered(pending, issues, replacements, etaTicks);
    }

    @Test
    void aWorkingOutcomeReportsWorkingWithNoOfferDetails() {
        Map<String, Object> t = OfferView.tree(SubmitOutcome.working(), 0);
        assertEquals("WORKING", t.get("state"));
        assertNull(t.get("hash"));
        assertEquals(0, ((Number) t.get("blocks")).intValue());
        assertEquals(0, ((Number) t.get("etaSeconds")).intValue());
        assertEquals(false, t.get("needsTerrainConfirm"));
        assertEquals(false, t.get("needsDestructiveConfirm"));
        assertEquals(List.of(), t.get("issues"));
    }

    @Test
    void anOfferedOutcomeCarriesTheHashBlockCountEtaAndConfirmFlags() {
        Issue issue = Issue.of(IssueCode.E_UNKNOWN_PART, "roof", List.of("node:7"), "unknown part id");
        ReplacementSummary replacements = new ReplacementSummary(3, 0, 1, 2, 4, List.of());
        Map<String, Object> t = OfferView.tree(offered(replacements, 400, List.of(issue)), 5);
        assertEquals("OFFERED", t.get("state"));
        assertEquals("0123abcd", t.get("hash"));
        assertEquals(5, ((Number) t.get("blocks")).intValue());
        assertEquals(20, ((Number) t.get("etaSeconds")).intValue(), "400 ticks at 20 ticks a second");
        assertEquals(2, ((Number) t.get("terrainCut")).intValue());
        assertEquals(4, ((Number) t.get("terrainFill")).intValue());
        assertEquals(3, ((Number) t.get("fluids")).intValue());
        assertEquals(0, ((Number) t.get("leaves")).intValue());
        assertEquals(1, ((Number) t.get("emptyContainers")).intValue());
        assertEquals(true, t.get("needsTerrainConfirm"), "2 cut + 4 fill means terraforming");
        assertEquals(true, t.get("needsDestructiveConfirm"), "3 fluids + 1 container are destructive");
        List<?> issues = (List<?>) t.get("issues");
        assertEquals(1, issues.size());
        Map<?, ?> entry = (Map<?, ?>) issues.get(0);
        assertEquals("micradrone.build.issue.e_unknown_part", entry.get("childKey"));
        assertEquals("unknown part id", entry.get("message"),
                "the message is the technical text a fixing AI reply needs, not a child line");
    }

    @Test
    void noTerrainAndNoReplacementsMeansNoConfirmations() {
        ReplacementSummary clean = new ReplacementSummary(0, 0, 0, 0, 0, List.of());
        Map<String, Object> t = OfferView.tree(offered(clean, 0, List.of()), 3);
        assertEquals(false, t.get("needsTerrainConfirm"));
        assertEquals(false, t.get("needsDestructiveConfirm"));
    }

    @Test
    void aFailedOutcomeCarriesTheIssuesWithChildKeys() {
        Issue unloaded = Issue.of(IssueCode.E_SITE_BLOCKED, "unloaded", List.of("manifest"), "chunk not loaded");
        Map<String, Object> t = OfferView.tree(SubmitOutcome.failed(List.of(unloaded)), 0);
        assertEquals("FAILED", t.get("state"));
        Map<?, ?> entry = (Map<?, ?>) ((List<?>) t.get("issues")).get(0);
        assertEquals("micradrone.build.issue.site_unloaded", entry.get("childKey"),
                "the same child-facing key ChildMessages.issueLine picks");
        assertEquals("chunk not loaded", entry.get("message"));
    }

    @Test
    void aRejectionIsItsOwnStateCarryingTechnicalMessages() {
        Map<String, Object> t = OfferView.rejectedTree(
                List.of(new OfferView.Entry(null, "chunked upload unsupported: index=0 count=3"),
                        OfferView.Entry.of(Issue.of(IssueCode.E_TERRAFORM_UNCONFIRMED, "",
                                List.of("manifest"), "terraform needs a confirm"))));
        assertEquals("REJECTED", t.get("state"));
        assertNull(t.get("hash"));
        assertEquals(0, ((Number) t.get("blocks")).intValue());
        List<?> issues = (List<?>) t.get("issues");
        assertEquals(2, issues.size());
        assertNull(((Map<?, ?>) issues.get(0)).get("childKey"),
                "a payload-shape refusal has no child-facing line");
        assertEquals("micradrone.build.issue.e_terraform_unconfirmed",
                ((Map<?, ?>) issues.get(1)).get("childKey"));
    }

    @Test
    void theOfferTableRoundTripsThroughTheJsonWriter() {
        ReplacementSummary replacements = new ReplacementSummary(1, 2, 0, 3, 4, List.of());
        String json = MiniJson.write(OfferView.tree(offered(replacements, 200, List.of()), 7));
        Map<?, ?> parsed = (Map<?, ?>) MiniJson.parse(json);
        assertEquals("OFFERED", parsed.get("state"));
        assertEquals(7, ((Number) parsed.get("blocks")).intValue());
        assertEquals(10, ((Number) parsed.get("etaSeconds")).intValue());
        assertEquals(true, parsed.get("needsTerrainConfirm"));
    }
}
