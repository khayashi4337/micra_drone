package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.AssemblyStep;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.SiteSurvey;
import io.github.khayashi4337.micradrone.build.compile.TerrainSummary;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.parts.AssemblyKind;
import io.github.khayashi4337.micradrone.build.plan.TemplateBundle;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApprovalDeskTest {
    private static final UUID OWNER = new UUID(0, 1);
    private static final UUID OTHER = new UUID(0, 2);
    private static final String OW = TestManifests.DIM;
    private static final long NOW = 1_000L;

    private final PlacementManifest hut = TestManifests.smallHut();
    private final SurveyCache surveys = new SurveyCache();
    private final SiteSurvey survey = SiteSurvey.air(OW, hut.worldBounds());

    private ApprovalDesk desk(List<Issue> issues, ReplacementSummary summary, PlacementManifest m) {
        return desk(issues, summary, m, OW, survey.digest());
    }

    /** {@code dimension} and {@code offeredDigest} are what the caller claims; the compiled plan keeps its own. */
    private ApprovalDesk desk(List<Issue> issues, ReplacementSummary summary, PlacementManifest m,
                            String dimension, String offeredDigest) {
        surveys.pin(survey, NOW);
        ApprovalDesk d = new ApprovalDesk();
        CompiledPlan compiled = new CompiledPlan(m, List.of(), new TerrainSummary(summary.terrainCut(), summary.terrainFill()),
                Map.of(), m.worldBounds(), survey.digest());
        d.offer(OWNER, dimension, compiled, new SafetyReport(issues, summary),
                new PlanSubmission(SemanticPlan.empty("p"), TemplateBundle.EMPTY, JobKind.BUILD, null, null),
                offeredDigest, NOW);
        return d;
    }

    private static ReplacementSummary nothing() {
        return new ReplacementSummary(0, 0, 0, 0, 0, List.of());
    }

    private ApprovalDecision approve(ApprovalDesk d, String hash, Approver who, Confirmations c, List<AcceptedRisk> risks, long now) {
        return d.approve(new ApprovalRequest(hash, OW, OWNER, risks, c), who, TestManifests.REGISTRY_VERSION, surveys, now,
                () -> "job-7");
    }

    private static Approver owner(boolean creative) {
        return new Approver(OWNER, false, OW, creative, null);
    }

    private static ApprovalRejection reason(ApprovalDecision d) {
        return assertInstanceOf(ApprovalDecision.Rejected.class, d).reason();
    }

    @Test
    void theOwnerApprovesTheServersOwnHashAndGetsAPendingJob() {
        ApprovalDesk d = desk(List.of(), nothing(), hut);
        ApprovalDecision.Approved ok = assertInstanceOf(ApprovalDecision.Approved.class,
                approve(d, hut.hash(), owner(true), Confirmations.NONE, List.of(), NOW + 1));
        ConstructionJob job = ok.job();
        assertEquals("job-7", job.jobId());
        assertEquals(JobState.PENDING_APPROVAL, job.state());
        assertEquals(MaterialPolicy.CREATIVE_FREE, job.materialPolicy());
        assertEquals(OWNER, job.ownerUuid());
        assertEquals("claim-job-7", job.claimId());
        assertEquals(hut.placements().size(), job.total());
        assertTrue(d.pending(OWNER).isEmpty(), "a pending approval is used once");
    }

    @Test
    void aFakeManifestHashIsRejected() {
        ApprovalDesk d = desk(List.of(), nothing(), hut);
        assertEquals(ApprovalRejection.HASH_MISMATCH, reason(approve(d, "0".repeat(64), owner(true), Confirmations.NONE,
                List.of(), NOW + 1)));
        assertTrue(d.pending(OWNER).isPresent(), "a wrong hash does not burn the real pending approval");
    }

    @Test
    void approvalFromAnotherDimensionIsRejected() {
        ApprovalDesk d = desk(List.of(), nothing(), hut);
        Approver inNether = new Approver(OWNER, false, "minecraft:the_nether", true, null);
        assertEquals(ApprovalRejection.DIMENSION_MISMATCH, reason(approve(d, hut.hash(), inNether, Confirmations.NONE, List.of(),
                NOW + 1)));
    }

    @Test
    void anOfferInADimensionOtherThanTheManifestsIsRejected() {
        ApprovalDesk d = desk(List.of(), nothing(), hut, "minecraft:the_nether", survey.digest());
        Approver inNether = new Approver(OWNER, false, "minecraft:the_nether", true, null);
        assertEquals(ApprovalRejection.DIMENSION_MISMATCH, reason(d.approve(
                new ApprovalRequest(hut.hash(), "minecraft:the_nether", OWNER, List.of(), Confirmations.NONE),
                inNether, TestManifests.REGISTRY_VERSION, surveys, NOW + 1, () -> "job-7")),
                "the pending dimension is bound to the compiled manifest's own dimension");
    }

    @Test
    void anOfferWhoseOwnDimensionDisagreesWithTheManifestIsRejected() {
        ApprovalDesk d = desk(List.of(), nothing(), hut, "minecraft:the_nether", survey.digest());
        assertEquals(ApprovalRejection.DIMENSION_MISMATCH, reason(approve(d, hut.hash(), owner(true), Confirmations.NONE,
                List.of(), NOW + 1)),
                "a pending offer recorded in the wrong dimension is inconsistent even for an owner in the right one");
    }

    @Test
    void aSurveyDigestOtherThanTheCompilesIsRejected() {
        SiteSurvey other = SiteSurvey.air(OW, new Box(1, 60, 1, 3, 70, 3));
        surveys.pin(other, NOW);
        ApprovalDesk d = desk(List.of(), nothing(), hut, OW, other.digest());
        assertEquals(ApprovalRejection.SURVEY_MISMATCH, reason(approve(d, hut.hash(), owner(true), Confirmations.NONE,
                List.of(), NOW + 1)),
                "a live survey that is not the one the manifest was compiled from does not count");
    }

    @Test
    void expiryRegistrySurveyAndOwnershipAreChecked() {
        ApprovalDesk d = desk(List.of(), nothing(), hut);
        assertEquals(ApprovalRejection.NOT_OWNER, reason(approve(d, hut.hash(), new Approver(OTHER, false, OW, true, null),
                Confirmations.NONE, List.of(), NOW + 1)));
        assertEquals(ApprovalRejection.REGISTRY_CHANGED, reason(d.approve(new ApprovalRequest(hut.hash(), OW, OWNER, List.of(),
                Confirmations.NONE), owner(true), "another-version", surveys, NOW + 1, () -> "job-7")));
        assertEquals(ApprovalRejection.EXPIRED, reason(approve(d, hut.hash(), owner(true), Confirmations.NONE, List.of(),
                NOW + ApprovalDesk.APPROVAL_TTL_TICKS + 1)));
        assertEquals(ApprovalRejection.NO_PENDING, reason(approve(d, hut.hash(), owner(true), Confirmations.NONE, List.of(),
                NOW + 2)), "an expired approval is gone");
        ApprovalDesk d2 = desk(List.of(), nothing(), hut);
        surveys.expire(NOW + SurveyCache.SURVEY_TTL_TICKS + 1);
        SurveyCache empty = new SurveyCache();
        assertEquals(ApprovalRejection.SURVEY_EXPIRED, reason(d2.approve(new ApprovalRequest(hut.hash(), OW, OWNER, List.of(),
                Confirmations.NONE), owner(true), TestManifests.REGISTRY_VERSION, empty, NOW + 1, () -> "job-7")));
    }

    @Test
    void anOperatorMayApproveAndBecomesTheOwner() {
        ApprovalDesk d = desk(List.of(), nothing(), hut);
        ApprovalDecision.Approved ok = assertInstanceOf(ApprovalDecision.Approved.class, approve(d, hut.hash(),
                new Approver(OTHER, true, OW, false, null), Confirmations.NONE, List.of(), NOW + 1));
        assertEquals(OTHER, ok.job().ownerUuid(), "the job's owner is who approved it (D-12)");
        assertEquals(MaterialPolicy.SURVIVAL_CONSUME, ok.job().materialPolicy());
    }

    @Test
    void errorsBlockAndOnlyAcceptableRisksCanBeAccepted() {
        Issue blocked = Issue.of(IssueCode.E_SITE_BLOCKED, "unloaded", List.of("manifest"), "x");
        Issue warn = Issue.of(IssueCode.W_UNMODELED, List.of("press"), "x");
        ApprovalDesk d = desk(List.of(blocked, warn), nothing(), hut);
        assertEquals(ApprovalRejection.BLOCKING_ISSUES, reason(approve(d, hut.hash(), owner(true), Confirmations.NONE,
                List.of(new AcceptedRisk(warn.id(), "")), NOW + 1)));
        assertEquals(ApprovalRejection.RISK_NOT_ACCEPTABLE, reason(approve(d, hut.hash(), owner(true), Confirmations.NONE,
                List.of(new AcceptedRisk(blocked.id(), "")), NOW + 1)), "an E- code cannot be accepted away");
        assertEquals(ApprovalRejection.RISK_NOT_ACCEPTABLE, reason(approve(d, hut.hash(), owner(true), Confirmations.NONE,
                List.of(new AcceptedRisk("W-NOT-THERE:x", "")), NOW + 1)));
        ApprovalDesk onlyWarn = desk(List.of(warn), nothing(), hut);
        ApprovalDecision.Approved ok = assertInstanceOf(ApprovalDecision.Approved.class, approve(onlyWarn, hut.hash(), owner(true),
                Confirmations.NONE, List.of(new AcceptedRisk(warn.id(), "")), NOW + 1));
        assertEquals(List.of(warn.id()), ok.job().acceptedRiskIds());
    }

    @Test
    void terraformingAndDestructiveReplacementNeedTheirConfirmations() {
        ReplacementSummary both = new ReplacementSummary(2, 0, 0, 9, 18, List.of(new IntPos(0, 63, 0)));
        ApprovalDesk d = desk(List.of(), both, hut);
        ApprovalDecision.Rejected t = assertInstanceOf(ApprovalDecision.Rejected.class,
                approve(d, hut.hash(), owner(true), Confirmations.NONE, List.of(), NOW + 1));
        assertEquals(ApprovalRejection.TERRAFORM_UNCONFIRMED, t.reason());
        assertEquals(IssueCode.E_TERRAFORM_UNCONFIRMED, t.blocking().get(0).code());
        assertEquals("9", t.blocking().get(0).data().get("cut"));
        assertEquals(ApprovalRejection.DESTRUCTIVE_UNCONFIRMED, reason(approve(d, hut.hash(), owner(true),
                new Confirmations(true, false), List.of(), NOW + 1)));
        assertInstanceOf(ApprovalDecision.Approved.class, approve(d, hut.hash(), owner(true), new Confirmations(true, true), List.of(),
                NOW + 1));
    }

    @Test
    void assembliesWaitForTheirPhase() {
        PlacementManifest h = TestManifests.smallHut();
        PlacementManifest withAssembly = new PlacementManifest(h.manifestVersion(), h.planId(), h.planRevision(),
                h.registryVersion(), h.dimension(), h.frame(), h.worldBounds(), h.placements(),
                List.of(new AssemblyStep("g", AssemblyKind.WINDMILL, new IntPos(0, 64, 0), List.of(0), null)), h.bom(), h.phases(),
                h.hash());
        ApprovalDesk d = desk(List.of(), nothing(), withAssembly);
        assertEquals(ApprovalRejection.ASSEMBLY_NOT_AVAILABLE, reason(approve(d, h.hash(), owner(true), Confirmations.NONE,
                List.of(), NOW + 1)));
    }

    @Test
    void anOwnerWhoLeavesLosesThePendingApproval() {
        ApprovalDesk d = desk(List.of(), nothing(), hut);
        d.dropOwner(OWNER);
        assertEquals(ApprovalRejection.NO_PENDING, reason(approve(d, hut.hash(), owner(true), Confirmations.NONE, List.of(),
                NOW + 1)));
    }
}
