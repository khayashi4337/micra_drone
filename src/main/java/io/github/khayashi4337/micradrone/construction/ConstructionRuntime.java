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
import io.github.khayashi4337.micradrone.construction.core.ChatChannel;
import io.github.khayashi4337.micradrone.construction.core.ChildMessages;
import io.github.khayashi4337.micradrone.construction.core.ClaimBook;
import io.github.khayashi4337.micradrone.construction.core.CompiledPlan;
import io.github.khayashi4337.micradrone.construction.core.Confirmations;
import io.github.khayashi4337.micradrone.construction.core.ConstructionBudget;
import io.github.khayashi4337.micradrone.construction.core.ConstructionJob;
import io.github.khayashi4337.micradrone.construction.core.ControlResult;
import io.github.khayashi4337.micradrone.construction.core.FileWalSink;
import io.github.khayashi4337.micradrone.construction.core.ItemCatalog;
import io.github.khayashi4337.micradrone.construction.core.JobCodec;
import io.github.khayashi4337.micradrone.construction.core.JobFiles;
import io.github.khayashi4337.micradrone.construction.core.JobLoad;
import io.github.khayashi4337.micradrone.construction.core.JobProgram;
import io.github.khayashi4337.micradrone.construction.core.JobRecord;
import io.github.khayashi4337.micradrone.construction.core.JobService;
import io.github.khayashi4337.micradrone.construction.core.JobState;
import io.github.khayashi4337.micradrone.construction.core.JobStatus;
import io.github.khayashi4337.micradrone.construction.core.JobUpdate;
import io.github.khayashi4337.micradrone.construction.core.JobViews;
import io.github.khayashi4337.micradrone.construction.core.JobWorld;
import io.github.khayashi4337.micradrone.construction.core.MessageKey;
import io.github.khayashi4337.micradrone.construction.core.NioFileSystem;
import io.github.khayashi4337.micradrone.construction.core.OperatingBox;
import io.github.khayashi4337.micradrone.construction.core.OrphanSweep;
import io.github.khayashi4337.micradrone.construction.core.PauseReason;
import io.github.khayashi4337.micradrone.construction.core.PendingApproval;
import io.github.khayashi4337.micradrone.construction.core.PersistenceEnvelope;
import io.github.khayashi4337.micradrone.construction.core.PlacementSurvey;
import io.github.khayashi4337.micradrone.construction.core.PlanCompilation;
import io.github.khayashi4337.micradrone.construction.core.PlanSubmission;
import io.github.khayashi4337.micradrone.construction.core.QuietPolicy;
import io.github.khayashi4337.micradrone.construction.core.RecoveryChoice;
import io.github.khayashi4337.micradrone.construction.core.RecoveryDecision;
import io.github.khayashi4337.micradrone.construction.core.RecoveryPlanner;
import io.github.khayashi4337.micradrone.construction.core.ReplacementSummary;
import io.github.khayashi4337.micradrone.construction.core.SafetyEnvelope;
import io.github.khayashi4337.micradrone.construction.core.SafetyReport;
import io.github.khayashi4337.micradrone.construction.core.SaveTypes;
import io.github.khayashi4337.micradrone.construction.core.ServerWorkerPool;
import io.github.khayashi4337.micradrone.construction.core.SiteBoxLimits;
import io.github.khayashi4337.micradrone.construction.core.SiteClaim;
import io.github.khayashi4337.micradrone.construction.core.SubmitOutcome;
import io.github.khayashi4337.micradrone.construction.core.SupplySettings;
import io.github.khayashi4337.micradrone.construction.core.SupplySettingsBook;
import io.github.khayashi4337.micradrone.construction.core.SurveyCache;
import io.github.khayashi4337.micradrone.construction.core.TickInput;
import io.github.khayashi4337.micradrone.construction.core.WorkResult;
import io.github.khayashi4337.micradrone.construction.core.WorldCell;
import io.github.khayashi4337.micradrone.construction.core.WriteAheadLog;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
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
    /** One progress push a second per running job (20 vanilla ticks), on top of the state-change pushes. */
    private static final long PROGRESS_INTERVAL_TICKS = 20L;
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

    /** {@code -Dmicradrone.devBarriers=true} arms the crash-barrier hook ({@link #installBarrier}); off: inert. */
    public static final String DEV_BARRIERS_PROPERTY = "micradrone.devBarriers";

    private final MinecraftServer server;
    private final PartTypeRegistry registry;
    private final ClaimBook claims;
    /** What the claim book looked like when it was last written (see {@link #saveClaimsIfChanged}). */
    private String savedClaimsDigest = "";
    /** Every claim's supply switches (Task 27a): whether its jobs may take from the owner's inventory. */
    private final SupplySettingsBook supply = new SupplySettingsBook();
    /** What the supply switches looked like when they were last written (see {@link #saveSupplyIfChanged}). */
    private String savedSupplyDigest = "";
    /** The claim ids that have a supply file on the disk (written here or read at start-up). */
    private final Set<String> savedSupplyFiles = new HashSet<>();
    /** Supply files that exist but could not be read: never silently removed or written over (F-2). */
    private final Set<String> unreadableSupply = new HashSet<>();
    private final NioFileSystem fs;
    private final JobFiles files;
    private final WriteAheadLog wal;
    private final JobService service;
    private final ServerWorkerPool workers;
    private final ApprovalDesk desk;
    private final SurveyCache surveys;
    private final PlacementGuard guard;
    private final RuntimeJobWorld world;
    /** The read-only, chunk-loading world recovery and the recover command judge evidence against. */
    private final JobWorld recoveryWorld;
    private final Checkpoints checkpoints;
    private final DroneShow show = new DroneShow();
    private final ItemCatalog itemCatalog = id -> BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(id));
    /** M2b: the path each owner's newest submission came in on; it gates {@link #tell}. */
    private final ChatChannel chat = new ChatChannel();

    private final Map<UUID, Submit> submissions = new LinkedHashMap<>();
    private final Map<UUID, SubmitOutcome> lastSubmits = new HashMap<>();
    /** Manifest hash -> owner of a live pending approval, so {@link #manifestTree} finds offered manifests. */
    private final Map<String, UUID> pendingOwners = new HashMap<>();
    /**
     * A write of the records that could not be confirmed stops construction for the whole run: a job that keeps
     * placing without its files would build state a restart cannot prove (04 F-2). Set once; reads may come from
     * a devkit query thread, so it is volatile.
     */
    private volatile boolean recordingFailed;
    private long jobSeq;
    private long lastTickWorkNanos;

    private ConstructionRuntime(MinecraftServer server) {
        this.server = server;
        this.registry = BuildingParts.registry();
        Path root = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("micradrone");
        this.fs = new NioFileSystem(root);
        this.files = new JobFiles(fs);
        WriteAheadLog opened;
        ClaimBook loadedClaims = null;
        boolean broken = false;
        try {
            fs.cleanTemp();
            opened = WriteAheadLog.open(new FileWalSink(root.resolve(JobFiles.WAL_DIR)));
            loadedClaims = files.loadClaims(ConstructionConfig.claimsMaxPerOwner()).orElse(null);
        } catch (IOException | RuntimeException e) {
            // a log or a claims book that cannot be read is never written over: construction stops, the game runs on
            MicraDrone.LOGGER.error("construction records could not be read; construction is stopped", e);
            opened = WriteAheadLog.inMemory();
            broken = true;
        }
        this.wal = opened;
        this.claims = loadedClaims != null ? loadedClaims : new ClaimBook(ConstructionConfig.claimsMaxPerOwner());
        this.service = new JobService(ConstructionConfig.budget(), claims, registry, ConstructionConfig.fastStructure(),
                ConstructionConfig.continueWhileOffline(), wal);
        this.workers = new ServerWorkerPool(ServerWorkerPool.DEFAULT_THREADS, ServerWorkerPool.DEFAULT_MAX_QUEUED,
                server::execute);
        this.desk = new ApprovalDesk();
        this.surveys = new SurveyCache();
        this.guard = new PlacementGuard(server, this::ownerName);
        this.world = new RuntimeJobWorld(server, guard, claims, supply);
        this.recoveryWorld = RuntimeJobWorld.recovery(server);
        this.recordingFailed = broken;
        this.checkpoints = new Checkpoints(server, service, wal, () -> recordingFailed, this::haltRecording);
        loadAll();
    }

    /**
     * Start-up (Task 25, 04 F-2), in the order the evidence allows: the placed registries, every saved job through
     * {@link JobFiles#loadJob} and {@link RecoveryPlanner#decide} (never a silent re-run of a broken one), the WAL
     * fold ({@link JobService#recoverAll}) that rewinds what the world lost and pauses what it cannot decide, the
     * persisted result, a first durable point once the whole of it is on the disk, and the orphan sweep last so a
     * job file that failed to read is never mistaken for refuse.
     */
    private void loadAll() {
        if (recordingFailed) {
            return;
        }
        loadRegistries();
        loadSupplyBook();
        Set<String> unreadable = new HashSet<>();
        List<ConstructionJob> kept = loadJobs(unreadable);
        JobService.RecoveryReport report = service.recoverAll(recoveryWorld);
        for (String line : report.reasons()) {
            MicraDrone.LOGGER.warn("start-up recovery waits for an owner: {}", line);
        }
        saveAll();
        watchJobIds();
        if (!recordingFailed && wal.lastRun() > wal.durableUpTo()) {
            checkpoints.make(server.getTickCount());
        }
        sweepOrphans(kept, unreadable);
    }

    /** Every claim's placed registry; a claim with none keeps the empty one the service makes on demand. */
    private void loadRegistries() {
        List<String> claimFiles;
        try {
            claimFiles = fs.list(JobFiles.CLAIMS_DIR);
        } catch (IOException e) {
            haltRecording("the placed registries could not be listed", e);
            return;
        }
        for (String f : claimFiles) {
            String rest = f.substring(JobFiles.CLAIMS_DIR.length());
            int slash = rest.indexOf('/');
            if (slash < 0 || !rest.substring(slash + 1).equals(JobFiles.PLACED_FILE)) {
                continue;
            }
            String claimId = rest.substring(0, slash);
            try {
                files.loadRegistry(claimId)
                        .ifPresent(reg -> service.registries().put(claimId, reg));
            } catch (IOException | RuntimeException e) {
                MicraDrone.LOGGER.error("a placed registry could not be read ({})", f, e);
            }
        }
    }

    /**
     * Every claim's {@code supply.bin} (Task 27a). A file that cannot be read is kept on the disk and never
     * silently reset or removed (F-2): the claim falls back to the safe default - inventory disallowed -
     * until the owner sets the switch again.
     */
    private void loadSupplyBook() {
        List<String> claimFiles;
        try {
            claimFiles = fs.list(JobFiles.CLAIMS_DIR);
        } catch (IOException e) {
            haltRecording("the supply settings could not be listed", e);
            return;
        }
        for (String f : claimFiles) {
            String rest = f.substring(JobFiles.CLAIMS_DIR.length());
            int slash = rest.indexOf('/');
            if (slash < 0 || !rest.substring(slash + 1).equals(JobFiles.SUPPLY_FILE)) {
                continue;
            }
            String claimId = rest.substring(0, slash);
            try {
                Optional<SupplySettings> found = files.loadSupply(claimId);
                if (found.isEmpty()) {
                    continue;
                }
                supply.allowInventory(claimId, found.get().inventoryAllowed());
                savedSupplyFiles.add(claimId);
            } catch (IOException | RuntimeException e) {
                unreadableSupply.add(claimId);
                MicraDrone.LOGGER.error("a claim's supply settings could not be read ({})", f, e);
            }
        }
        savedSupplyDigest = supplyDigest();
    }

    /** The book's change digest: every entry's claimId and flag, claim-id sorted (like the claims digest). */
    private String supplyDigest() {
        return supply.entries().entrySet().stream()
                .map(e -> e.getKey() + (e.getValue().inventoryAllowed() ? "+" : "-"))
                .sorted().collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * The supply switches follow their claim (Task 27a): a released or gone claim's entry is dropped - the
     * same release/rollback/cancel path its other files take - and the files are written on the tick the
     * book changed, like {@link #saveClaimsIfChanged}. A file that could not be read at start-up stays.
     */
    private void saveSupplyIfChanged() {
        for (String id : List.copyOf(supply.entries().keySet())) {
            SiteClaim c = claims.find(id).orElse(null);
            if (c == null || c.released()) {
                supply.remove(id);
            }
        }
        String digest = supplyDigest();
        if (digest.equals(savedSupplyDigest)) {
            return;
        }
        try {
            for (Map.Entry<String, SupplySettings> e : supply.entries().entrySet()) {
                files.saveSupply(e.getKey(), e.getValue());
            }
            // unreadableSupply is deliberately not in savedSupplyFiles, so an unreadable file is never deleted here
            for (String id : savedSupplyFiles) {
                if (!supply.entries().containsKey(id)) {
                    files.deleteSupply(id);
                }
            }
            savedSupplyFiles.clear();
            savedSupplyFiles.addAll(supply.entries().keySet());
            savedSupplyDigest = digest;
        } catch (IOException | RuntimeException e) {
            haltRecording("a claim's supply settings could not be saved", e);
        }
    }

    /**
     * Every {@code jobs/<id>/job.bin}, decoded and decided: a readable job goes to the service with the planner's
     * answer (and its recovery mark); a job whose own file cannot be read stays on disk for the owner rather than
     * becoming an orphan, and a job whose other files failed becomes a broken record the owner answers for.
     */
    private List<ConstructionJob> loadJobs(Set<String> unreadable) {
        List<ConstructionJob> kept = new ArrayList<>();
        List<String> jobFiles;
        try {
            jobFiles = fs.list(ConstructionJob.JOBS_DIR);
        } catch (IOException e) {
            haltRecording("the jobs folder could not be listed", e);
            return kept;
        }
        for (String f : jobFiles) {
            if (!f.endsWith("/" + JobFiles.JOB_FILE)) {
                continue;
            }
            String dir = f.substring(0, f.length() - JobFiles.JOB_FILE.length());
            String id = dir.substring(ConstructionJob.JOBS_DIR.length(), dir.length() - 1);
            ConstructionJob saved;
            try {
                byte[] bytes = fs.read(f).orElseThrow(() -> new IOException("job file listed but unreadable: " + f));
                PersistenceEnvelope envelope = PersistenceEnvelope.fromBytes(bytes);
                if (!SaveTypes.JOB.equals(envelope.type())) {
                    throw new IOException("a " + envelope.type() + " file where a job belongs: " + f);
                }
                saved = JobCodec.fromTree(SaveTypes.migrations().payloadOf(envelope));
            } catch (IOException | RuntimeException e) {
                unreadable.add(id);
                MicraDrone.LOGGER.error("a job's record could not be read ({})", f, e);
                continue;
            }
            try {
                JobLoad load = files.loadJob(saved, claims);
                RecoveryDecision decision = RecoveryPlanner.decide(saved, load);
                if (OrphanSweep.forgettableJobs(List.of(decision.job()), claims).contains(decision.job().jobId())) {
                    continue;
                }
                if (load instanceof JobLoad.Loaded l) {
                    service.addLoaded(decision.job(), l, decision.fromLog());
                } else if (load instanceof JobLoad.Broken b) {
                    service.addBroken(decision.job(), b.manifestOrNull(), b.nodeTypes(), operatingBoxOf(decision.job(), b),
                            b.reasons());
                }
                kept.add(decision.job());
            } catch (IOException | RuntimeException e) {
                unreadable.add(id);
                MicraDrone.LOGGER.error("a job's files could not be read ({})", id, e);
            }
        }
        return kept;
    }

    /** The operating box a broken job's record still needs: the claim's own, else the manifest's bounds. */
    private Box operatingBoxOf(ConstructionJob job, JobLoad.Broken broken) {
        if (broken.manifestOrNull() == null) {
            // no manifest means no record at all: the box is unused, only the bare job is kept for the owner
            return new Box(0, 0, 0, 0, 0, 0);
        }
        return claims.find(job.claimId()).map(SiteClaim::operatingBox).orElseGet(broken.manifestOrNull()::worldBounds);
    }

    /**
     * Job ids carry the overworld clock plus a per-runtime sequence ({@link #nextJobId}); after a crash the clock
     * may come back unchanged, so the sequence resumes past every id this boot already holds.
     */
    private void watchJobIds() {
        String prefix = "job-" + server.overworld().getGameTime() + "-";
        for (JobStatus st : service.statuses()) {
            String id = st.jobId();
            if (!id.startsWith(prefix)) {
                continue;
            }
            try {
                jobSeq = Math.max(jobSeq, Long.parseLong(id.substring(prefix.length())) + 1);
            } catch (NumberFormatException e) {
                // an id another tool issued is simply not a sequence member
            }
        }
    }

    /** Every live record's files plus the claim book and the placed registries, forced to the disk. */
    private void saveAll() {
        for (JobRecord r : service.records()) {
            saveJob(r);
        }
        try {
            files.saveClaims(claims);
            for (SiteClaim c : claims.all()) {
                if (!c.released()) {
                    files.saveRegistry(service.registry(c.claimId()));
                }
            }
        } catch (IOException | RuntimeException e) {
            haltRecording("the claims or a placed registry could not be saved", e);
        }
    }

    /** What a finished job leaves behind once its claim is gone: files no job keeps alive are deleted. */
    private void sweepOrphans(List<ConstructionJob> kept, Set<String> unreadable) {
        List<String> all;
        try {
            all = files.allFiles();
        } catch (IOException e) {
            MicraDrone.LOGGER.warn("the orphan sweep could not list the construction's files", e);
            return;
        }
        List<String> orphans = new ArrayList<>(OrphanSweep.orphanFiles(all, kept, claims));
        // a job directory that failed to read is a question for the owner, never refuse for the sweep
        orphans.removeIf(f -> unreadable.stream()
                .anyMatch(id -> f.startsWith(ConstructionJob.JOBS_DIR + id + "/")));
        int swept = 0;
        for (String f : orphans) {
            try {
                fs.delete(f);
                swept++;
            } catch (IOException | RuntimeException e) {
                MicraDrone.LOGGER.warn("an orphan file could not be deleted: {}", f);
            }
        }
        if (swept > 0) {
            MicraDrone.LOGGER.info("orphan sweep removed {} construction file(s)", swept);
        }
    }

    /** One job's files, durable before the caller's answer is given; a failure stops construction. */
    private void saveJob(JobRecord r) {
        if (r == null) {
            return;
        }
        try {
            files.saveJob(r, service.ledgers());
        } catch (IOException | RuntimeException e) {
            haltRecording("job " + r.job().jobId() + " could not be saved", e);
        }
    }

    private void saveJob(String jobId) {
        saveJob(service.record(jobId).orElse(null));
    }

    /**
     * The claim book goes to the disk the tick it changes (a claim is reserved or released), not only at save events:
     * a job file whose claim is not on the disk cannot be recovered without asking the owner ("claim: missing"), which
     * a real kill of the game a few seconds after the approval produced. The window left is the one tick in which the
     * claim was reserved and the first runs were already logged.
     */
    private void saveClaimsIfChanged() {
        String digest = claims.all().stream().map(c -> c.claimId() + (c.released() ? "-" : "+")).sorted()
                .collect(java.util.stream.Collectors.joining(","));
        if (digest.equals(savedClaimsDigest)) {
            return;
        }
        try {
            files.saveClaims(claims);
            savedClaimsDigest = digest;
        } catch (IOException | RuntimeException e) {
            haltRecording("the claims could not be saved", e);
        }
    }

    /**
     * The one place a failed durable write turns into a stopped runtime: the message goes to the log once, every
     * later failure just keeps the flag (the records may already be half-written, so nothing writes on).
     */
    private void haltRecording(String what, Throwable e) {
        if (!recordingFailed) {
            recordingFailed = true;
            MicraDrone.LOGGER.error("construction is stopped: {}", what, e);
        }
    }

    /** True once a record the durability chain needs could not be written; {@link BuildCommands} shows the halt line. */
    public boolean halted() {
        return recordingFailed;
    }

    /** The runtime of this server, while it is up (between ServerStartedEvent and ServerStoppingEvent). */
    public static Optional<ConstructionRuntime> of(MinecraftServer server) {
        ConstructionRuntime r = instance;
        return r != null && r.server == server ? Optional.of(r) : Optional.empty();
    }

    /** A submission over the build channel moves the owner's chat to the quiet panel view (M2b). */
    public void markPanel(UUID owner) {
        chat.markPanel(owner);
    }

    /** A submission over a {@code /micradrone build} command returns the owner's chat to the command lines. */
    public void markCommand(UUID owner) {
        chat.markCommand(owner);
    }

    /**
     * The one door every child-facing chat line leaves through (M2b): an owner whose newest
     * submission came over the panel misses the lines {@link QuietPolicy} names - the panel's own
     * packets already show the same facts without the command machinery. Called only on the server
     * main thread, which is the one thread {@link ChatChannel} is built for.
     */
    private void tell(UUID owner, MessageKey key) {
        if (!chat.isPanel(owner) || !QuietPolicy.suppressForPanel(key.key())) {
            ServerMessages.send(server, owner, key);
        }
    }

    /**
     * The same door for a line built as a {@link Component} (a status line, a rejection line);
     * {@code key} is the {@link ChildMessages} key the line was built from, for the policy check.
     */
    private void tell(UUID owner, String key, Component component) {
        if (!chat.isPanel(owner) || !QuietPolicy.suppressForPanel(key)) {
            ServerMessages.send(server, owner, component);
        }
    }

    /**
     * Issue lines take the same door one by one: a suppressed line still gets the server-log entry
     * {@link ServerMessages#sendIssues} would have written, so the log never loses a record.
     */
    private void tellIssues(UUID owner, List<Issue> issues) {
        if (!chat.isPanel(owner)) {
            ServerMessages.sendIssues(server, owner, issues);
            return;
        }
        List<Issue> shown = new ArrayList<>(issues.size());
        for (Issue issue : issues) {
            if (QuietPolicy.suppressForPanel(ChildMessages.issueLine(issue).key())) {
                // sendIssues is skipped for this line, so its usual log entry is written here
                MicraDrone.LOGGER.info("construction issue {}: {}", issue.id(), issue.message());
            } else {
                shown.add(issue);
            }
        }
        ServerMessages.sendIssues(server, owner, shown);
    }

    /**
     * A new submission replaces any outcome display but never a running one: an owner has at most one
     * submit in flight (the worker's slot and the survey/read phases count alike).
     */
    public void submit(ServerPlayer player, PlanSubmission submission) {
        UUID owner = player.getUUID();
        if (recordingFailed) {
            // a new job whose first file cannot be written must not be accepted at all
            recordOutcome(owner, SubmitOutcome.failed(List.of()), 0);
            tell(owner, MessageKey.of(ChildMessages.HALTED));
            return;
        }
        if (workers.busy(owner) || submissions.containsKey(owner)) {
            tell(owner, MessageKey.of(ChildMessages.SUBMIT_BUSY));
            return;
        }
        recordOutcome(owner, SubmitOutcome.working(), 0);
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
            tell(owner, ChildMessages.rejection(ApprovalRejection.DIMENSION_MISMATCH),
                    ServerMessages.rejection(ApprovalRejection.DIMENSION_MISMATCH, List.of()));
            recordOutcome(owner, SubmitOutcome.failed(List.of()), 0);
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
            recordOutcome(owner, SubmitOutcome.failed(List.of()), 0);
            tell(owner, MessageKey.of(ChildMessages.SUBMIT_BUSY));
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
        recordOutcome(owner, SubmitOutcome.offered(pending, pending.issues(), safety.replacements(), eta),
                manifest.placements().size());
        tell(owner, MessageKey.of(ChildMessages.SUBMIT_OK, manifest.placements().size(),
                eta / TICKS_PER_SECOND, pending.manifestHash()));
        ReplacementSummary replacements = safety.replacements();
        if (replacements.needsTerraformConfirm()) {
            tell(owner, MessageKey.of(ChildMessages.TERRAIN_CONFIRM, replacements.terrainCut(),
                    replacements.terrainFill()));
        }
        if (replacements.needsDestructiveConfirm()) {
            tell(owner, MessageKey.of(ChildMessages.DESTRUCTIVE_CONFIRM, replacements.fluids(),
                    replacements.leaves(), replacements.emptyContainers()));
        }
        tellIssues(owner, pending.issues());
    }

    private void finishFailed(UUID owner, List<Issue> issues) {
        recordOutcome(owner, SubmitOutcome.failed(issues), 0);
        tell(owner, MessageKey.of(ChildMessages.SUBMIT_ISSUES, issues.size()));
        tellIssues(owner, issues);
    }

    /**
     * Records an owner's newest submit outcome and pushes it to their client the moment it leaves
     * WORKING (M2's BuildOfferPayload). Each outcome object is written once, so each transition
     * pushes exactly once; WORKING itself is not pushed.
     */
    private void recordOutcome(UUID owner, SubmitOutcome outcome, int blocks) {
        lastSubmits.put(owner, outcome);
        if (!SubmitOutcome.WORKING.equals(outcome.state())) {
            BuildNetwork.pushOffer(server, owner, outcome, blocks);
        }
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
            if (recordingFailed) {
                // an approved job that cannot be saved must not enter the service at all
                tell(player.getUUID(), MessageKey.of(ChildMessages.HALTED));
                return decision;
            }
            CompiledPlan compiled = approved.candidate().compiled();
            service.admitApproved(approved.job(), compiled.manifest(), compiled.nodeTypes(),
                    JobProgram.build(compiled.manifest()), compiled.operatingBox());
            saveJob(approved.job().jobId());
            tell(player.getUUID(), MessageKey.of(ChildMessages.APPROVE_OK, approved.job().jobId()));
        } else if (decision instanceof ApprovalDecision.Rejected rejected) {
            for (Issue issue : rejected.blocking()) {
                MicraDrone.LOGGER.info("approval refused by {}: {}", rejected.reason(), issue.id());
            }
            tell(player.getUUID(), ChildMessages.rejection(rejected.reason()),
                    ServerMessages.rejection(rejected.reason(), rejected.blocking()));
        }
        return decision;
    }

    public ControlResult cancel(UUID requester, boolean op, String jobId) {
        ControlResult result = service.cancel(jobId, requester, op);
        if (result == ControlResult.OK) {
            saveJob(jobId);
        }
        tell(requester, result == ControlResult.OK
                ? MessageKey.of(ChildMessages.CANCELLED, jobId) : MessageKey.of(ChildMessages.control(result)));
        return result;
    }

    public ControlResult resume(UUID requester, boolean op, String jobId, boolean skipSiteChanges) {
        ControlResult result = service.resume(jobId, requester, op, skipSiteChanges);
        if (result == ControlResult.OK) {
            saveJob(jobId);
        }
        tell(requester, result == ControlResult.OK
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
        if (result == ControlResult.OK) {
            saveJob(jobId);
            saveJob(newJobId);
        }
        JobStatus created = result == ControlResult.OK ? service.status(newJobId).orElse(null) : null;
        if (created != null) {
            tell(requester, ChildMessages.STATUS_LINE, ServerMessages.status(created));
        } else {
            tell(requester, MessageKey.of(ChildMessages.control(result)));
        }
        return result;
    }

    /**
     * The owner's (or an operator's) answer to a recovery-paused job (Task 25): adopt or discard the part of the log
     * the evidence could not decide, or end the job with repair or fail. {@link JobService#recover} itself checks
     * the owner and the state; the resulting files are written before the answer goes out, and a job that still
     * waits hears the ask line again.
     */
    public ControlResult recover(UUID requester, boolean op, String jobId, RecoveryChoice choice) {
        String newJobId = nextJobId();
        ControlResult result = service.recover(jobId, requester, op, choice, newJobId, server.getTickCount(),
                recoveryWorld);
        if (result == ControlResult.OK) {
            saveJob(jobId);
            if (choice == RecoveryChoice.REPAIR) {
                saveJob(newJobId);
            }
            JobStatus shown = service.status(choice == RecoveryChoice.REPAIR ? newJobId : jobId).orElse(null);
            if (shown != null) {
                tell(requester, ChildMessages.STATUS_LINE, ServerMessages.status(shown));
            }
            JobStatus after = service.status(jobId).orElse(null);
            if (after != null && after.state() == JobState.PAUSED && after.shownPause() == PauseReason.RECOVERY_NEEDED) {
                tell(requester, MessageKey.of(ChildMessages.RECOVER_ASK, after.jobId(), recoveryReason(after.lastError())));
            }
        } else {
            tell(requester, MessageKey.of(ChildMessages.control(result)));
        }
        return result;
    }

    /**
     * The owner's (or an operator's) ask to undo a claim (04 F-14). F-5's confirmation stand-in: without
     * {@code confirm} the answer is only how many blocks would come out, decided by the same check the run would
     * pass; with it, the ROLLBACK job is made (its id comes from {@link #nextJobId} like an approval's) and the
     * answer is the new job's own status line.
     */
    public ControlResult rollback(UUID requester, boolean op, String claimId, boolean confirm) {
        if (!confirm) {
            ControlResult check = service.checkRollback(claimId, requester, op);
            if (check != ControlResult.OK) {
                tell(requester, MessageKey.of(ChildMessages.control(check)));
                return check;
            }
            tell(requester, MessageKey.of(ChildMessages.ROLLBACK_ASK, service.rollbackPlan(claimId).size(), claimId));
            return ControlResult.OK;
        }
        if (recordingFailed) {
            // like approve: a job whose first file cannot be written must not enter the service at all
            tell(requester, MessageKey.of(ChildMessages.HALTED));
            return ControlResult.WRONG_STATE;
        }
        String newJobId = nextJobId();
        ControlResult result = service.rollback(claimId, requester, op, newJobId, server.getTickCount());
        if (result == ControlResult.OK) {
            saveJob(newJobId);
            JobStatus created = service.status(newJobId).orElse(null);
            if (created != null) {
                tell(requester, ChildMessages.STATUS_LINE, ServerMessages.status(created));
            }
        } else {
            tell(requester, MessageKey.of(ChildMessages.control(result)));
        }
        return result;
    }

    /**
     * The one door to the per-claim inventory switch (Task 27a): only the claim's owner - or an op - may set
     * it, never on a missing or released claim. The change is saved in the same call so a crash cannot lose
     * an answer the player already saw. A job of the claim paused on missing materials picks the new switch
     * up on its own at the next review ({@link JobService#RETRY_INTERVAL_TICKS} re-runs the paused run, and
     * the port reads this book live), so nothing extra is needed here. The result is returned to the caller;
     * the child-facing line, when there is one, is the caller's to phrase.
     */
    public ControlResult setInventoryAllowed(UUID requester, boolean op, String claimId, boolean allowed) {
        SiteClaim claim = claims.find(claimId).orElse(null);
        if (claim == null || claim.released()) {
            return ControlResult.NOT_FOUND;
        }
        if (!claim.ownerUuid().equals(requester) && !op) {
            return ControlResult.NOT_ALLOWED;
        }
        supply.allowInventory(claimId, allowed);
        saveSupplyIfChanged();
        return ControlResult.OK;
    }

    /** The claim's inventory switch (false for an unknown claim), for the progress document and 27b's panel. */
    public boolean inventoryAllowed(String claimId) {
        return supply.inventoryAllowed(claimId);
    }

    /** The claim's supply answer for the adult-facing list command: the switch and the chests in use. */
    public record SupplyInfo(String claimId, UUID ownerUuid, boolean inventoryAllowed, List<IntPos> chests) {
    }

    /** The claim's supply info, or empty when the claim does not exist or was released. */
    public Optional<SupplyInfo> supplyInfo(String claimId) {
        SiteClaim claim = claims.find(claimId).orElse(null);
        if (claim == null || claim.released()) {
            return Optional.empty();
        }
        return Optional.of(new SupplyInfo(claimId, claim.ownerUuid(), supply.inventoryAllowed(claimId),
                world.chests().positions(claim)));
    }

    /**
     * The devkit's crash barrier (Task 25): installed on the log and, when the sink is the file one, inside it for
     * the mid-frame point - and only while {@code -Dmicradrone.devBarriers=true}; without the property the call is a
     * no-op, so a production server can never be held by it.
     */
    public void installBarrier(WriteAheadLog.Barrier barrier) {
        if (!Boolean.getBoolean(DEV_BARRIERS_PROPERTY)) {
            return;
        }
        if (barrier instanceof DevBarrier dev) {
            dev.bind(service, wal);
        }
        wal.setBarrier(barrier);
        if (wal.sink() instanceof FileWalSink sink) {
            sink.setBarrier(barrier);
        }
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
        List<JobUpdate> updates = List.of();
        if (!recordingFailed) {
            try {
                updates = service.tick(new TickInput(now, server.getAverageTickTimeNanos() / NANOS_PER_MILLI),
                        world);
            } catch (RuntimeException e) {
                // a WAL write that did not go through stops construction, it never silently runs on
                haltRecording("a job's run could not be recorded", e);
            }
            saveClaimsIfChanged();
            saveSupplyIfChanged();
            for (JobUpdate u : updates) {
                // a state change is a new job.bin before the owner hears of it
                if (u.stateChanged()) {
                    saveJob(u.job().jobId());
                }
            }
            notifyOwners(updates, now);
            show.onUpdates(server, updates);
            checkpoints.tickEnd(now);
        }
        lastTickWorkNanos = System.nanoTime() - t0;
    }

    /**
     * Tick step (4): the owner hears a state change once, plus shortages and fresh conflicts as they
     * land. The same changes go out as progress pushes (M2's BuildProgressPayload), and a RUNNING
     * job pushes once a second even when its update list is empty - a job starved of allowance by
     * the budget emits no update at all, so the interval reads the statuses, not the updates.
     */
    private void notifyOwners(List<JobUpdate> updates, long now) {
        for (JobUpdate u : updates) {
            ConstructionJob job = u.job();
            if (u.stateChanged()) {
                JobStatus status = service.status(job.jobId()).orElse(null);
                if (job.state() == JobState.VERIFIED) {
                    tell(job.ownerUuid(), MessageKey.of(ChildMessages.DONE));
                } else if (job.state() == JobState.PARTIAL) {
                    // skipped + conflicts + the deviations the last repair round gave up on (lastError's numbers)
                    int unplaced = status == null ? 0 : status.skipped() + status.conflicts() + status.unrepaired();
                    tell(job.ownerUuid(), MessageKey.of(ChildMessages.PARTIAL, unplaced));
                } else if (status != null) {
                    tell(job.ownerUuid(), ChildMessages.STATUS_LINE, ServerMessages.status(status));
                }
                if (job.state() == JobState.PAUSED && job.pauseReason() == PauseReason.RECOVERY_NEEDED) {
                    tell(job.ownerUuid(), MessageKey.of(ChildMessages.RECOVER_ASK, job.jobId(),
                            recoveryReason(status == null ? "" : status.lastError())));
                }
                // the terminal transition pushes too, so the client always sees a job's last document
                if (status != null) {
                    BuildNetwork.pushProgress(server, job.ownerUuid(), status);
                } else if (job.state().terminal()) {
                    // a rollback that finished released its claim and dropped its record: the panel still needs the last document
                    BuildNetwork.pushFinalProgress(server, job.ownerUuid(), job);
                }
            }
            for (var item : u.shortage()) {
                tell(job.ownerUuid(),
                        MessageKey.of(ChildMessages.SHORTAGE, item.itemId(), item.count()));
            }
            if (!u.newConflicts().isEmpty()) {
                tell(job.ownerUuid(),
                        MessageKey.of(ChildMessages.CONFLICTS, u.newConflicts().size()));
            }
        }
        if (now % PROGRESS_INTERVAL_TICKS == 0) {
            for (JobStatus status : service.statuses()) {
                if (status.state() == JobState.RUNNING) {
                    BuildNetwork.pushProgress(server, status.owner(), status);
                }
            }
        }
    }

    /** The reasons a recovery pause asks about: the log part's own answer, without the internal prefix. */
    private static String recoveryReason(String lastError) {
        return lastError.startsWith(JobService.RECOVERY_AMBIGUOUS)
                ? lastError.substring(JobService.RECOVERY_AMBIGUOUS.length()) : lastError;
    }

    /**
     * A returning owner is asked once for every job still waiting on their answer (the start-up fold cannot send
     * chat to an offline player, so the ask is re-sent here instead of relying on the tick updates).
     */
    void onLogin(ServerPlayer player) {
        UUID owner = player.getUUID();
        for (JobStatus st : service.statuses()) {
            if (st.owner().equals(owner) && st.state() == JobState.PAUSED
                    && st.shownPause() == PauseReason.RECOVERY_NEEDED) {
                tell(owner, MessageKey.of(ChildMessages.RECOVER_ASK, st.jobId(), recoveryReason(st.lastError())));
            }
        }
    }

    /**
     * Inside a flushed save ({@code LevelEvent.Save}, posted per level before the IO worker is awaited): this
     * dimension's jobs are written, and under the overworld the claim book and the placed registries with them.
     * A write that cannot be confirmed stops construction rather than earning a durable point over half a save.
     */
    void onSave(ServerLevel level) {
        String dim = dimensionId(level);
        try {
            for (JobRecord r : service.records()) {
                if (r.job().dimension().equals(dim)) {
                    files.saveJob(r, service.ledgers());
                }
            }
            // the claims file and the placed registries go with the overworld's own save
            if (level.dimension() == Level.OVERWORLD) {
                files.saveClaims(claims);
                for (SiteClaim c : claims.all()) {
                    if (!c.released()) {
                        files.saveRegistry(service.registry(c.claimId()));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            haltRecording("a job's files could not be saved with the world", e);
        }
    }

    /**
     * After the shutdown's own flushed save (every level, {@code noSave} cleared) has written the job files:
     * the durable point for everything the log still held. Runs without it would be folded again at the next
     * start; that is legal, but a written point is the honest end of a clean stop.
     */
    void afterStop() {
        if (!recordingFailed && wal.lastRun() > wal.durableUpTo()) {
            try {
                service.markDurable();
            } catch (RuntimeException e) {
                MicraDrone.LOGGER.error("the durable point could not be written at stop", e);
            }
        }
    }

    /** A departing player loses a live offer at once and frees their worker/submit slots. */
    void onLogout(UUID player) {
        desk.dropOwner(player);
        pendingOwners.values().removeIf(owner -> desk.pending(owner).isEmpty());
        workers.cancel(player);
        submissions.remove(player);
        chat.forget(player);
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
                // the drones go before the shutdown save so none is kept on disk; the runtime stays up while the
                // save events of stopServer still need it to write the job files
                r.shutdown();
            }
        }

        /** After the flushed shutdown save: the durable point, then the runtime is gone. */
        @SubscribeEvent
        public static void onServerStopped(ServerStoppedEvent event) {
            ConstructionRuntime r = instance;
            if (r != null && r.server == event.getServer()) {
                instance = null;
                r.afterStop();
            }
        }

        /** The durable write of the job files happens inside the save that covers them (Task 25). */
        @SubscribeEvent
        public static void onLevelSave(LevelEvent.Save event) {
            ConstructionRuntime r = instance;
            if (r != null && event.getLevel() instanceof ServerLevel level) {
                r.onSave(level);
            }
        }

        /** An owner coming online hears the ask of every job waiting on their recovery answer. */
        @SubscribeEvent
        public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
            ConstructionRuntime r = instance;
            if (r != null && event.getEntity() instanceof ServerPlayer player) {
                r.onLogin(player);
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
