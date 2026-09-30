package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Pending approvals and the approval check (04 F-3, D-3, D-12, D-27). Only a hash the server computed itself can be
 * approved, by its owner or an operator, in the same dimension, before it expires, on the same registry version and
 * pinned survey, with every blocking issue gone and the terraforming and destructive replacements confirmed.
 */
public final class ApprovalDesk {
    /** F-3's default of ten minutes, at 20 ticks per second. */
    public static final long APPROVAL_TTL_TICKS = 20L * 60L * 10L;
    static final String CLAIM_PREFIX = "claim-";
    static final String SUBJECT_MANIFEST = "manifest";
    static final String DATA_CUT = "cut";
    static final String DATA_FILL = "fill";
    static final String DATA_FLUIDS = "fluids";
    static final String DATA_LEAVES = "leaves";
    static final String DATA_CONTAINERS = "containers";

    private final Map<UUID, Candidate> pending = new HashMap<>();

    public PendingApproval offer(UUID owner, String dimension, CompiledPlan compiled, SafetyReport safety,
                                 PlanSubmission submission, String surveyDigest, long now) {
        if (compiled.manifest() == null) {
            throw new IllegalArgumentException("only a compiled manifest can wait for approval");
        }
        List<Issue> issues = new ArrayList<>(compiled.issues());
        issues.addAll(safety.issues());
        PendingApproval p = new PendingApproval(compiled.manifest().hash(), dimension, owner, now + APPROVAL_TTL_TICKS, issues,
                surveyDigest);
        pending.put(owner, new Candidate(p, compiled, safety, submission));
        return p;
    }

    public Optional<Candidate> pending(UUID owner) {
        return Optional.ofNullable(pending.get(owner));
    }

    public void dropOwner(UUID owner) {
        pending.remove(owner);
    }

    public void dropAll() {
        pending.clear();
    }

    public void expire(long now) {
        pending.values().removeIf(c -> now > c.approval().expiresTick());
    }

    public ApprovalDecision approve(ApprovalRequest req, Approver approver, String registryVersion, SurveyCache surveys, long now,
                                    Supplier<String> newJobId) {
        Candidate cand = pending.get(req.playerUuid());
        if (cand == null) {
            return rejected(ApprovalRejection.NO_PENDING);
        }
        PendingApproval pa = cand.approval();
        if (!approver.uuid().equals(pa.owner()) && !approver.operator()) {
            return rejected(ApprovalRejection.NOT_OWNER);
        }
        if (now > pa.expiresTick()) {
            pending.remove(req.playerUuid());
            return rejected(ApprovalRejection.EXPIRED);
        }
        if (!req.manifestHash().equals(pa.manifestHash())) {
            return rejected(ApprovalRejection.HASH_MISMATCH);
        }
        PlacementManifest m = cand.compiled().manifest();
        // the dimension the approval binds to is the compiled manifest's own: the dimensions the offer and the
        // request carried only serve as consistency checks against it (04 D-3)
        if (!approver.currentDimension().equals(m.dimension()) || !req.dimension().equals(m.dimension())
                || !pa.dimension().equals(m.dimension())) {
            return rejected(ApprovalRejection.DIMENSION_MISMATCH);
        }
        if (!registryVersion.equals(m.registryVersion())) {
            return new ApprovalDecision.Rejected(ApprovalRejection.REGISTRY_CHANGED, List.of(Issue.of(IssueCode.E_REGISTRY_VERSION,
                    List.of(SUBJECT_MANIFEST), "部品の登録簿の版が変わりました。もう一度送ってください")));
        }
        // the approval's survey must be the very survey the manifest was compiled against: another survey, however
        // fresh and pinned, means the blocks the manifest expects were never looked at (04 D-3)
        if (!pa.surveyDigest().equals(cand.compiled().surveyDigest())) {
            return rejected(ApprovalRejection.SURVEY_MISMATCH);
        }
        if (surveys.find(pa.surveyDigest(), now).isEmpty()) {
            return rejected(ApprovalRejection.SURVEY_EXPIRED);
        }
        Map<String, Issue> byId = new HashMap<>();
        for (Issue i : pa.issues()) {
            byId.put(i.id(), i);
        }
        Set<String> accepted = new HashSet<>();
        for (AcceptedRisk r : req.acceptedRisks()) {
            Issue i = byId.get(r.issueId());
            if (i == null || !i.acceptable()) {
                return rejected(ApprovalRejection.RISK_NOT_ACCEPTABLE);
            }
            accepted.add(i.id());
        }
        List<Issue> blocking = pa.issues().stream().filter(i -> i.isError() && !accepted.contains(i.id())).toList();
        if (!blocking.isEmpty()) {
            return new ApprovalDecision.Rejected(ApprovalRejection.BLOCKING_ISSUES, blocking);
        }
        if (!m.assemblies().isEmpty()) {
            return rejected(ApprovalRejection.ASSEMBLY_NOT_AVAILABLE);
        }
        ReplacementSummary s = cand.safety().replacements();
        if (s.needsTerraformConfirm() && !req.confirmations().terraform()) {
            return new ApprovalDecision.Rejected(ApprovalRejection.TERRAFORM_UNCONFIRMED, List.of(Issue.of(
                    IssueCode.E_TERRAFORM_UNCONFIRMED, "", List.of(SUBJECT_MANIFEST),
                    "地形を変えます(切る" + s.terrainCut() + "個、盛る" + s.terrainFill() + "個)。確かめてから承認してください",
                    Map.of(DATA_CUT, String.valueOf(s.terrainCut()), DATA_FILL, String.valueOf(s.terrainFill())), List.of())));
        }
        if (s.needsDestructiveConfirm() && !req.confirmations().destructive()) {
            return new ApprovalDecision.Rejected(ApprovalRejection.DESTRUCTIVE_UNCONFIRMED, List.of(Issue.of(
                    IssueCode.E_REPLACE_UNCONFIRMED, "", List.of(SUBJECT_MANIFEST),
                    "水・溶岩" + s.fluids() + "個、木の葉" + s.leaves() + "個、空の入れ物" + s.emptyContainers()
                            + "個を置き換えます。確かめてから承認してください",
                    Map.of(DATA_FLUIDS, String.valueOf(s.fluids()), DATA_LEAVES, String.valueOf(s.leaves()), DATA_CONTAINERS,
                            String.valueOf(s.emptyContainers())), List.of())));
        }
        MaterialPolicy policy = approver.forcedPolicy() != null ? approver.forcedPolicy()
                : approver.creative() ? MaterialPolicy.CREATIVE_FREE : MaterialPolicy.SURVIVAL_CONSUME;
        String jobId = newJobId.get();
        PlanSubmission sub = cand.submission();
        String claimId = sub.kind() == JobKind.BUILD ? CLAIM_PREFIX + jobId : sub.claimId();
        ConstructionJob job = ConstructionJob.create(jobId, approver.uuid(), pa.dimension(), pa.manifestHash(), sub.kind(),
                sub.parentJobId(), m.placements().size(), claimId, policy, now, List.copyOf(accepted.stream().sorted().toList()));
        pending.remove(req.playerUuid());
        return new ApprovalDecision.Approved(job, cand);
    }

    private static ApprovalDecision rejected(ApprovalRejection reason) {
        return new ApprovalDecision.Rejected(reason, List.of());
    }
}
