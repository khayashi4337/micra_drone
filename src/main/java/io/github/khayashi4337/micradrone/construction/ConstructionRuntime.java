package io.github.khayashi4337.micradrone.construction;

import com.mojang.authlib.GameProfile;
import io.github.khayashi4337.micradrone.MicraDrone;
import io.github.khayashi4337.micradrone.build.compile.PhaseRange;
import io.github.khayashi4337.micradrone.build.compile.PlaceableBlockPolicy;
import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.SiteSurvey;
import io.github.khayashi4337.micradrone.build.compile.SiteSurveyBuilder;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.PlanJson;
import io.github.khayashi4337.micradrone.build.model.Site;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import io.github.khayashi4337.micradrone.build.verify.SnapshotCollector;
import io.github.khayashi4337.micradrone.construction.core.AcceptedRisk;
import io.github.khayashi4337.micradrone.construction.core.ApprovalDecision;
import io.github.khayashi4337.micradrone.construction.core.ApprovalDesk;
import io.github.khayashi4337.micradrone.construction.core.ApprovalRejection;
import io.github.khayashi4337.micradrone.construction.core.ApprovalRequest;
import io.github.khayashi4337.micradrone.construction.core.Approver;
import io.github.khayashi4337.micradrone.construction.core.Candidate;
import io.github.khayashi4337.micradrone.construction.core.ChildMessages;
import io.github.khayashi4337.micradrone.construction.core.ClaimBook;
import io.github.khayashi4337.micradrone.construction.core.CompiledPlan;
import io.github.khayashi4337.micradrone.construction.core.Confirmations;
import io.github.khayashi4337.micradrone.construction.core.ConstructionBudget;
import io.github.khayashi4337.micradrone.construction.core.ConstructionJob;
import io.github.khayashi4337.micradrone.construction.core.ControlResult;
import io.github.khayashi4337.micradrone.construction.core.ItemCatalog;
import io.github.khayashi4337.micradrone.construction.core.JobProgram;
import io.github.khayashi4337.micradrone.construction.core.JobRecord;
import io.github.khayashi4337.micradrone.construction.core.JobService;
import io.github.khayashi4337.micradrone.construction.core.JobState;
import io.github.khayashi4337.micradrone.construction.core.JobStatus;
import io.github.khayashi4337.micradrone.construction.core.JobUpdate;
import io.github.khayashi4337.micradrone.construction.core.JobViews;
import io.github.khayashi4337.micradrone.construction.core.MessageKey;
import io.github.khayashi4337.micradrone.construction.core.OperatingBox;
import io.github.khayashi4337.micradrone.construction.core.PendingApproval;
import io.github.khayashi4337.micradrone.construction.core.PlacementSurvey;
import io.github.khayashi4337.micradrone.construction.core.PlanCompilation;
import io.github.khayashi4337.micradrone.construction.core.PlanSubmission;
import io.github.khayashi4337.micradrone.construction.core.ReplacementSummary;
import io.github.khayashi4337.micradrone.construction.core.SafetyEnvelope;
import io.github.khayashi4337.micradrone.construction.core.SafetyReport;
import io.github.khayashi4337.micradrone.construction.core.ServerWorkerPool;
import io.github.khayashi4337.micradrone.construction.core.SiteBoxLimits;
import io.github.khayashi4337.micradrone.construction.core.SubmitOutcome;
import io.github.khayashi4337.micradrone.construction.core.SurveyCache;
import io.github.khayashi4337.micradrone.construction.core.TickInput;
import io.github.khayashi4337.micradrone.construction.core.WorkResult;
import io.github.khayashi4337.micradrone.construction.core.WorldCell;
import io.github.khayashi4337.micradrone.construction.core.WriteAheadLog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The construction runtime of a live server (04 F-1..F-5, F-13). It owns the approval desk, the pinned
 * surveys, the worker pool and the job service, and runs the submit flow as a per-owner state machine spread
 * over ticks: survey the site in bounded columns, compile it on a worker thread (pure code only there), read
 * every placement position in bounded batches, run the safety envelope, then offer the approval. All world
 * access happens on the main thread; the one write path is {@link JobService#tick} (D-1).
 */
public final class ConstructionRuntime {
    /** Vanilla runs twenty ticks a second; the ETA the owner sees is in seconds. */
    private static final long TICKS_PER_SECOND = 20L;
    /** {@code getAverageTickTimeNanos} reports nanoseconds; {@link TickInput} wants milliseconds. */
    private static final double NANOS_PER_MILLI = 1_000_000.0;
    /** P4 ships no bundled templates (P11); submissions verify against an empty table. */
    private static final Map<String, String> BUNDLED_TEMPLATE_HASHES = Map.of();
    /** Issue key matching SafetyEnvelope's unloaded-subject convention. */
    private static final String KEY_UNLOADED = SafetyEnvelope.KEY_UNLOADED;
    /** The subject every site-level issue names. */
    private static final String SUBJECT_MANIFEST = "manifest";

    private static volatile ConstructionRuntime instance;

    /**
     * The phase of a submit in flight: SURVEY while columns are read, COMPILING while the worker owns it
     * (its result lands on the main thread between ticks), READ while the placement positions are read.
     */
    private enum SubmitPhase {
        SURVEY, COMPILING, READ
    }

    private static final class Submit {
        final PlanSubmission submission;
        final ServerLevel level;
        final boolean op;
        SubmitPhase phase = SubmitPhase.SURVEY;
        SiteSurveyBuilder builder;
        SiteSurvey survey;
        PlaceableBlockPolicy policy;
        CompiledPlan compiled;
        SnapshotCollector collector;
        Map<IntPos, WorldCell> cells;

        Submit(PlanSubmission submission, ServerLevel level, boolean op) {
            this.submission = submission;
            this.level = level;
            this.op = op;
        }
    }

    private final MinecraftServer server;
    private final PartTypeRegistry registry;
    private final ClaimBook claims;
    private final WriteAheadLog wal;
    private final JobService service;
    private final ServerWorkerPool workers;
    private final ApprovalDesk desk;
    private final SurveyCache surveys;
    private final PlacementGuard guard;
    private final RuntimeJobWorld world;
    private final DroneShow show = new DroneShow();
    private final ItemCatalog itemCatalog = id -> BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(id));

    private final Map<UUID, Submit> submissions = new LinkedHashMap<>();
    private final Map<UUID, SubmitOutcome> lastSubmits = new HashMap<>();
    /** Manifest hash -> owner of a live pending approval, so {@link #manifestTree} finds offered manifests. */
    private final Map<String, UUID> pendingOwners = new HashMap<>();
    private long jobSeq;
    private long lastTickWorkNanos;

    private ConstructionRuntime(MinecraftServer server) {
        this.server = server;
        this.registry = BuildingParts.registry();
        this.claims = new ClaimBook(ConstructionConfig.claimsMaxPerOwner());
        this.wal = WriteAheadLog.inMemory();
        this.service = new JobService(ConstructionConfig.budget(), claims, registry, ConstructionConfig.fastStructure(),
                ConstructionConfig.continueWhileOffline(), wal);
        this.workers = new ServerWorkerPool(ServerWorkerPool.DEFAULT_THREADS, ServerWorkerPool.DEFAULT_MAX_QUEUED,
                server::execute);
        this.desk = new ApprovalDesk();
        this.surveys = new SurveyCache();
        this.guard = new PlacementGuard(server, this::ownerName);
        this.world = new RuntimeJobWorld(server, guard);
    }

    /** The runtime of this server, while it is up (between ServerStartedEvent and ServerStoppingEvent). */
    public static Optional<ConstructionRuntime> of(MinecraftServer server) {
        ConstructionRuntime r = instance;
        return r != null && r.server == server ? Optional.of(r) : Optional.empty();
    }

    /**
     * A new submission replaces any outcome display but never a running one: an owner has at most one
     * submit in flight (the worker's slot and the survey/read phases count alike).
     */
    public void submit(ServerPlayer player, PlanSubmission submission) {
        UUID owner = player.getUUID();
        if (workers.busy(owner) || submissions.containsKey(owner)) {
            ServerMessages.send(player, MessageKey.of(ChildMessages.SUBMIT_BUSY));
            return;
        }
        lastSubmits.put(owner, SubmitOutcome.working());
        ServerLevel level = player.serverLevel();
        Submit s = new Submit(submission, level, player.hasPermissions(Commands.LEVEL_GAMEMASTERS));
        Site site = submission.plan().site();
        if (site == null) {
            // nothing to survey; the compile itself answers E-SITE-MISSING
            s.survey = emptySurvey(level);
            submissions.put(owner, s);
            startCompile(owner, s);
            return;
        }
        if (!site.dimension().equals(dimensionId(level))) {
            // D-27: the plan's dimension must be the one the submitter stands in
            ServerMessages.send(player, ServerMessages.rejection(ApprovalRejection.DIMENSION_MISMATCH, List.of()));
            lastSubmits.put(owner, SubmitOutcome.failed(List.of()));
            return;
        }
        Box worldBox = OperatingBox.toWorld(site.frame(), site.localBounds());
        // the site-box size is checked BEFORE the chunk scan and the survey: a localBounds at the coordinate
        // limit would otherwise make allLoaded or the survey walk an absurd box on the main thread
        Optional<Issue> sizeIssue = SiteBoxLimits.check(worldBox, ConstructionConfig.limits(level, s.op));
        if (sizeIssue.isPresent()) {
            submissions.remove(owner);
            finishFailed(owner, List.of(sizeIssue.get()));
            return;
        }
        if (!ServerSurveyor.allLoaded(level, worldBox)) {
            submissions.remove(owner);
            finishFailed(owner, List.of(unloadedIssue()));
            return;
        }
        if (!site.terrainDigest().isEmpty()) {
            Optional<SiteSurvey> pinned = surveys.find(site.terrainDigest(), server.getTickCount());
            if (pinned.isPresent()) {
                // F-3: a survey the server issued and pinned is reused, keeping the submission stable
                s.survey = pinned.get();
                submissions.put(owner, s);
                startCompile(owner, s);
                return;
            }
        }
        s.builder = new SiteSurveyBuilder(site.dimension(), worldBox);
        submissions.put(owner, s);
    }

    /** Survey done: pin it, build the placement policy on the main thread, then compile on a worker. */
    private void startCompile(UUID owner, Submit s) {
        surveys.pin(s.survey, server.getTickCount());
        // registries and tags are main-thread reads; the policy it makes is an immutable value
        PlaceableBlockPolicy policy = BuildTags.policy();
        s.policy = policy;
        s.phase = SubmitPhase.COMPILING;
        PlanSubmission sub = s.submission;
        PartTypeRegistry reg = registry;
        SiteSurvey survey = s.survey;
        boolean accepted = workers.<CompiledPlan>submit(owner,
                () -> PlanCompilation.compile(sub, reg, policy, survey, BUNDLED_TEMPLATE_HASHES),
                result -> onCompiled(owner, s, result));
        if (!accepted) {
            submissions.remove(owner);
            lastSubmits.put(owner, SubmitOutcome.failed(List.of()));
            ServerMessages.send(server, owner, MessageKey.of(ChildMessages.SUBMIT_BUSY));
        }
    }

    /** The compile's result, delivered on the main thread by the worker pool. */
    private void onCompiled(UUID owner, Submit s, WorkResult<CompiledPlan> result) {
        if (result instanceof WorkResult.Cancelled<CompiledPlan>) {
            submissions.remove(owner);
            return;
        }
        if (result instanceof WorkResult.Failed<CompiledPlan> failed) {
            MicraDrone.LOGGER.warn("plan compilation failed for {}", owner, failed.error());
            submissions.remove(owner);
            finishFailed(owner, List.of());
            return;
        }
        CompiledPlan compiled = ((WorkResult.Done<CompiledPlan>) result).value();
        if (compiled.manifest() == null) {
            submissions.remove(owner);
            finishFailed(owner, compiled.issues());
            return;
        }
        s.compiled = compiled;
        List<IntPos> positions = new ArrayList<>(compiled.manifest().placements().size());
        for (Placement p : compiled.manifest().placements()) {
            positions.add(p.pos());
        }
        s.collector = new SnapshotCollector(positions);
        s.cells = new HashMap<>();
        s.phase = SubmitPhase.READ;
    }

    /** Tick step (2): surveys advance by columns, placement reads by positions, both bounded per tick. */
    private void advanceSubmissions(long now) {
        for (Map.Entry<UUID, Submit> e : new ArrayList<>(submissions.entrySet())) {
            UUID owner = e.getKey();
            Submit s = e.getValue();
            switch (s.phase) {
                case SURVEY -> {
                    if (!ServerSurveyor.step(s.level, s.builder, SiteSurveyBuilder.SURVEY_COLUMNS_PER_TICK)) {
                        submissions.remove(owner);
                        finishFailed(owner, List.of(unloadedIssue()));
                    } else if (s.builder.done()) {
                        s.survey = s.builder.build();
                        startCompile(owner, s);
                    }
                }
                case COMPILING -> {
                    // the worker is running; its result is delivered on the main thread between ticks
                }
                case READ -> advanceRead(owner, s);
            }
        }
    }

    /** Reads the placement positions in bounded batches; an unloadable position ends the submission. */
    private void advanceRead(UUID owner, Submit s) {
        for (IntPos pos : s.collector.nextBatch(SnapshotCollector.DEFAULT_READS_PER_TICK)) {
            WorldCell cell = ServerStateReader.read(s.level, pos);
            if (!cell.loaded()) {
                submissions.remove(owner);
                finishFailed(owner, List.of(unloadedIssue()));
                return;
            }
            s.cells.put(pos, cell);
            s.collector.accept(pos, cell.observed());
        }
        while (s.collector.windowFull() && !s.collector.done()) {
            s.collector.takeWindow();
        }
        if (s.collector.done()) {
            submissions.remove(owner);
            offer(owner, s);
        }
    }

    /** Safety envelope on the main thread, then the offer the approval waits on. */
    private void offer(UUID owner, Submit s) {
        CompiledPlan compiled = s.compiled;
        PlacementManifest manifest = compiled.manifest();
        SafetyReport safety = SafetyEnvelope.check(manifest, compiled.terrain(), new PlacementSurvey(s.cells),
                ConstructionConfig.limits(s.level, s.op), s.policy, itemCatalog, pos -> Optional.empty());
        PendingApproval pending = desk.offer(owner, manifest.dimension(), compiled, safety, s.submission,
                compiled.surveyDigest(), server.getTickCount());
        pendingOwners.put(pending.manifestHash(), owner);
        long eta = etaTicks(manifest);
        lastSubmits.put(owner, SubmitOutcome.offered(pending, pending.issues(), safety.replacements(), eta));
        ServerPlayer to = server.getPlayerList().getPlayer(owner);
        ServerMessages.send(to, MessageKey.of(ChildMessages.SUBMIT_OK, manifest.placements().size(),
                eta / TICKS_PER_SECOND, pending.manifestHash()));
        ReplacementSummary replacements = safety.replacements();
        if (replacements.needsTerraformConfirm()) {
            ServerMessages.send(to, MessageKey.of(ChildMessages.TERRAIN_CONFIRM, replacements.terrainCut(),
                    replacements.terrainFill()));
        }
        if (replacements.needsDestructiveConfirm()) {
            ServerMessages.send(to, MessageKey.of(ChildMessages.DESTRUCTIVE_CONFIRM, replacements.fluids(),
                    replacements.leaves(), replacements.emptyContainers()));
        }
        ServerMessages.sendIssues(server, owner, pending.issues());
    }

    private void finishFailed(UUID owner, List<Issue> issues) {
        lastSubmits.put(owner, SubmitOutcome.failed(issues));
        ServerMessages.send(server, owner, MessageKey.of(ChildMessages.SUBMIT_ISSUES, issues.size()));
        ServerMessages.sendIssues(server, owner, issues);
    }

    /** The shared refusal for an unreadable site: E-SITE-BLOCKED on the manifest, keyed "unloaded". */
    private static Issue unloadedIssue() {
        return Issue.of(IssueCode.E_SITE_BLOCKED, KEY_UNLOADED, List.of(SUBJECT_MANIFEST),
                "読み込まれていない場所があります。近づいてから、もう一度送ってください");
    }

    /**
     * The owner's answer to an offer. Only the desk's checks decide; on approval the job is admitted to the
     * service with the server's own compiled manifest and operating box.
     */
    public ApprovalDecision approve(ServerPlayer player, String manifestHash, Confirmations confirmations,
                                    List<AcceptedRisk> acceptedRisks) {
        String dimension = dimensionId(player.serverLevel());
        ApprovalRequest request = new ApprovalRequest(manifestHash, dimension, player.getUUID(), acceptedRisks,
                confirmations);
        Approver approver = new Approver(player.getUUID(), player.hasPermissions(Commands.LEVEL_GAMEMASTERS), dimension,
                player.isCreative(), ConstructionConfig.forcedMaterialPolicy());
        ApprovalDecision decision = desk.approve(request, approver, registry.version(), surveys, server.getTickCount(),
                this::nextJobId);
        if (decision instanceof ApprovalDecision.Approved approved) {
            CompiledPlan compiled = approved.candidate().compiled();
            service.admitApproved(approved.job(), compiled.manifest(), compiled.nodeTypes(),
                    JobProgram.build(compiled.manifest()), compiled.operatingBox());
            ServerMessages.send(player, MessageKey.of(ChildMessages.APPROVE_OK, approved.job().jobId()));
        } else if (decision instanceof ApprovalDecision.Rejected rejected) {
            for (Issue issue : rejected.blocking()) {
                MicraDrone.LOGGER.info("approval refused by {}: {}", rejected.reason(), issue.id());
            }
            ServerMessages.send(player, ServerMessages.rejection(rejected.reason(), rejected.blocking()));
        }
        return decision;
    }

    public ControlResult cancel(UUID requester, boolean op, String jobId) {
        ControlResult result = service.cancel(jobId, requester, op);
        ServerMessages.send(server, requester, result == ControlResult.OK
                ? MessageKey.of(ChildMessages.CANCELLED, jobId) : MessageKey.of(ChildMessages.control(result)));
        return result;
    }

    public ControlResult resume(UUID requester, boolean op, String jobId, boolean skipSiteChanges) {
        ControlResult result = service.resume(jobId, requester, op, skipSiteChanges);
        ServerMessages.send(server, requester, result == ControlResult.OK
                ? MessageKey.of(ChildMessages.RESUMED, jobId) : MessageKey.of(ChildMessages.control(result)));
        return result;
    }

    /**
     * The owner's ask to re-check a finished job (03 0.4: the REPAIR it makes needs no approval). The new job's id
     * comes from {@link #nextJobId} like an approval's, and the answer line is the new job's own status line, so
     * the owner can follow it with {@code status}.
     */
    public ControlResult verify(UUID requester, boolean op, String jobId) {
        String newJobId = nextJobId();
        ControlResult result = service.beginVerify(jobId, requester, op, newJobId, server.getTickCount());
        JobStatus created = result == ControlResult.OK ? service.status(newJobId).orElse(null) : null;
        if (created != null) {
            ServerMessages.send(server, requester, ServerMessages.status(created));
        } else {
            ServerMessages.send(server, requester, MessageKey.of(ChildMessages.control(result)));
        }
        return result;
    }

    /** The newest submit outcome of an owner (null when this server run has seen none). */
    public SubmitOutcome lastSubmit(UUID owner) {
        return lastSubmits.get(owner);
    }

    /** One job as the shared JSON tree, or null when the id is unknown. */
    public Map<String, Object> statusTree(String jobId) {
        return service.status(jobId).map(JobViews::statusTree).orElse(null);
    }

    /** Every known job as the shared {@code {"jobs": [...]}} tree. */
    public Map<String, Object> jobsTree() {
        return JobViews.jobsTree(service.statuses());
    }

    /** A manifest by hash - a live pending approval's, a running job's, or an in-flight submit's; else null. */
    public Map<String, Object> manifestTree(String hash) {
        PlacementManifest m = findManifest(hash);
        if (m == null) {
            return null;
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("hash", m.hash());
        t.put("manifestVersion", (long) m.manifestVersion());
        t.put("planId", m.planId());
        t.put("planRevision", (long) m.planRevision());
        t.put("registryVersion", m.registryVersion());
        t.put("dimension", m.dimension());
        t.put("bounds", PlanJson.boxTree(m.worldBounds()));
        t.put("placements", (long) m.placements().size());
        t.put("assemblies", (long) m.assemblies().size());
        Map<String, Object> bom = new LinkedHashMap<>();
        m.bom().forEach((id, n) -> bom.put(id, n.longValue()));
        t.put("bom", bom);
        List<Object> phases = new ArrayList<>();
        for (PhaseRange phase : m.phases()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("phase", phase.phase().name());
            p.put("from", (long) phase.fromIndex());
            p.put("to", (long) phase.toIndexExclusive());
            phases.add(p);
        }
        t.put("phases", phases);
        return t;
    }

    private PlacementManifest findManifest(String hash) {
        UUID owner = pendingOwners.get(hash);
        if (owner != null) {
            Optional<Candidate> pending = desk.pending(owner);
            if (pending.isPresent()) {
                return pending.get().compiled().manifest();
            }
            pendingOwners.remove(hash);
        }
        for (JobRecord r : service.records()) {
            if (hash.equals(r.manifest().hash())) {
                return r.manifest();
            }
        }
        for (Submit s : submissions.values()) {
            if (s.compiled != null && hash.equals(s.compiled.manifest().hash())) {
                return s.compiled.manifest();
            }
        }
        return null;
    }

    public JobService jobs() {
        return service;
    }

    public String registryVersion() {
        return registry.version();
    }

    /**
     * The next job id: the persisted overworld clock plus a sequence of this runtime, so a restart can never
     * reissue an old id ({@link ConstructionJob#ID_PATTERN}).
     */
    public String nextJobId() {
        return "job-" + server.overworld().getGameTime() + "-" + jobSeq++;
    }

    /** Wall-clock nanos this runtime spent inside the last server tick (Task 33 feeds it to MsptStats). */
    public long lastTickWorkNanos() {
        return lastTickWorkNanos;
    }

    /** The per-tick work of the runtime (the brief's order), all on the main thread inside ServerTickEvent.Pre. */
    void tick() {
        long t0 = System.nanoTime();
        long now = server.getTickCount();
        desk.expire(now);
        surveys.expire(now);
        pendingOwners.values().removeIf(owner -> desk.pending(owner).isEmpty());
        advanceSubmissions(now);
        List<JobUpdate> updates = service.tick(new TickInput(now, server.getAverageTickTimeNanos() / NANOS_PER_MILLI),
                world);
        notifyOwners(updates);
        show.onUpdates(server, updates);
        checkpointIfWanted();
        lastTickWorkNanos = System.nanoTime() - t0;
    }

    /**
     * A job may end only under a durable point covering its runs, so a job that is waiting gets a flushed
     * save now. The in-memory WAL makes the marker a no-op for persistence; Task 25 swaps this for the
     * spaced Checkpoints adapter and a file sink, keeping the call in this one place.
     */
    private void checkpointIfWanted() {
        if (service.checkpointWanted()) {
            server.saveAllChunks(true, true, false);
            service.markDurable();
        }
    }

    /** Tick step (4): the owner hears a state change once, plus shortages and fresh conflicts as they land. */
    private void notifyOwners(List<JobUpdate> updates) {
        for (JobUpdate u : updates) {
            ConstructionJob job = u.job();
            if (u.stateChanged()) {
                JobStatus status = service.status(job.jobId()).orElse(null);
                if (job.state() == JobState.VERIFIED) {
                    ServerMessages.send(server, job.ownerUuid(), MessageKey.of(ChildMessages.DONE));
                } else if (job.state() == JobState.PARTIAL) {
                    // skipped + conflicts + the deviations the last repair round gave up on (lastError's numbers)
                    int unplaced = status == null ? 0 : status.skipped() + status.conflicts() + status.unrepaired();
                    ServerMessages.send(server, job.ownerUuid(), MessageKey.of(ChildMessages.PARTIAL, unplaced));
                } else if (status != null) {
                    ServerMessages.send(server, job.ownerUuid(), ServerMessages.status(status));
                }
            }
            for (var item : u.shortage()) {
                ServerMessages.send(server, job.ownerUuid(),
                        MessageKey.of(ChildMessages.SHORTAGE, item.itemId(), item.count()));
            }
            if (!u.newConflicts().isEmpty()) {
                ServerMessages.send(server, job.ownerUuid(),
                        MessageKey.of(ChildMessages.CONFLICTS, u.newConflicts().size()));
            }
        }
    }

    /** A departing player loses a live offer at once and frees their worker/submit slots. */
    void onLogout(UUID player) {
        desk.dropOwner(player);
        pendingOwners.values().removeIf(owner -> desk.pending(owner).isEmpty());
        workers.cancel(player);
        submissions.remove(player);
    }

    void shutdown() {
        workers.close();
        desk.dropAll();
        submissions.clear();
        show.discardAll(server);
    }

    private static String dimensionId(ServerLevel level) {
        return level.dimension().location().toString();
    }

    /** The name a fake player gets when its owner is offline; the profile cache holds it for online owners too. */
    private String ownerName(UUID owner) {
        GameProfileCache cache = server.getProfileCache();
        if (cache != null) {
            Optional<GameProfile> cached = cache.get(owner);
            if (cached.isPresent()) {
                return cached.get().getName();
            }
        }
        return owner.toString();
    }

    /** A one-column air survey for a site-less plan: the compile answers E-SITE-MISSING before reading it. */
    private static SiteSurvey emptySurvey(ServerLevel level) {
        Box one = new Box(0, level.getMinBuildHeight(), 0, 0, level.getMinBuildHeight(), 0);
        return SiteSurvey.air(dimensionId(level), one);
    }

    /** The ETA the submit message shows: the fast phases at the fast rate, the rest at the drone cadence. */
    private static long etaTicks(PlacementManifest m) {
        int fast = 0;
        for (Placement p : m.placements()) {
            if (JobService.FAST_PHASES.contains(p.phase())) {
                fast++;
            }
        }
        return ConstructionBudget.etaTicks(fast, m.placements().size() - fast, ConstructionConfig.budget());
    }

    /**
     * The game-bus handlers, static so {@code NeoForge.EVENT_BUS.register(Events.class)} picks them up.
     * The runtime exists only between the server's start and its stop.
     */
    public static final class Events {
        private Events() {
        }

        @SubscribeEvent
        public static void onServerStarted(ServerStartedEvent event) {
            instance = new ConstructionRuntime(event.getServer());
            MicraDrone.LOGGER.info("MicraDrone: construction runtime ready");
        }

        /** ServerTickEvent.Pre runs inside the measured tick window, so the budget sees this work's cost (D-1). */
        @SubscribeEvent
        public static void onServerTick(ServerTickEvent.Pre event) {
            ConstructionRuntime r = instance;
            if (r != null && r.server == event.getServer()) {
                r.tick();
            }
        }

        @SubscribeEvent
        public static void onServerStopping(ServerStoppingEvent event) {
            ConstructionRuntime r = instance;
            if (r != null && r.server == event.getServer()) {
                instance = null;
                r.shutdown();
            }
        }

        /** The debug commands are registered on the dispatcher, independent of the runtime's lifetime. */
        @SubscribeEvent
        public static void onRegisterCommands(RegisterCommandsEvent event) {
            BuildCommands.register(event.getDispatcher());
        }

        @SubscribeEvent
        public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
            ConstructionRuntime r = instance;
            if (r != null) {
                r.onLogout(event.getEntity().getUUID());
            }
        }

        /** A show drone reloaded from disk is a leftover of a previous run; it is refused (N-27). */
        @SubscribeEvent
        public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
            DroneShow.onEntityJoin(event);
        }
    }
}
