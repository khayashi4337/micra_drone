package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.BlockMatch;
import io.github.khayashi4337.micradrone.build.compile.Conflict;
import io.github.khayashi4337.micradrone.build.compile.ObservedBlock;
import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import io.github.khayashi4337.micradrone.build.verify.CompareScope;
import io.github.khayashi4337.micradrone.build.verify.Deviation;
import io.github.khayashi4337.micradrone.build.verify.DeviationKind;
import io.github.khayashi4337.micradrone.build.verify.RepairPlan;
import io.github.khayashi4337.micradrone.build.verify.RepairPlanner;
import io.github.khayashi4337.micradrone.build.verify.SnapshotCollector;
import io.github.khayashi4337.micradrone.build.verify.SnapshotDiff;
import io.github.khayashi4337.micradrone.build.verify.VolatileProps;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Drives every job one server tick at a time: claims for newly approved jobs (one after another, so overlapping
 * approvals cannot both win), the owner's presence, the budget's admission and allowances (04 F-2), placement, L7's
 * verification in bounded reads and its repair rounds (03 L7), and the owner's controls (D-12). This is the only path
 * that changes the world (D-1).
 */
public final class JobService {
    public static final int RETRY_INTERVAL_TICKS = 20;
    public static final Set<BuildPhase> FAST_PHASES = EnumSet.of(BuildPhase.SITE_PREP, BuildPhase.STRUCTURE, BuildPhase.ENVELOPE);
    private static final Set<JobState> ACTIVE = EnumSet.of(JobState.RUNNING, JobState.VERIFYING, JobState.REPAIRING);
    private static final Set<JobState> PLACING = EnumSet.of(JobState.RUNNING, JobState.REPAIRING);
    private static final Set<JobState> AWAY_PAUSES = EnumSet.of(JobState.QUEUED, JobState.RUNNING, JobState.VERIFYING,
            JobState.REPAIRING);
    private static final Set<PauseReason> RETRIED = EnumSet.of(PauseReason.CHUNK_UNLOADED, PauseReason.MATERIALS_MISSING,
            PauseReason.NO_ROOM);
    private static final Set<JobState> ROLLBACKABLE = EnumSet.of(JobState.VERIFIED, JobState.PARTIAL, JobState.FAILED,
            JobState.CANCELLED);
    /** The lastError prefix of a job whose log waits for the owner's answer; the reasons follow it. */
    public static final String RECOVERY_AMBIGUOUS = "recovery-ambiguous:";

    private final BudgetConfig budgetConfig;
    private final ConstructionBudget budget;
    private final ClaimBook claims;
    private final PartTypeRegistry registry;
    private final boolean fastStructure;
    private final boolean continueWhileOffline;
    private final LedgerBook ledgers = new LedgerBook();
    private final WriteAheadLog wal;
    private final Map<String, PlacedRegistry> registries = new HashMap<>();
    private final LinkedHashMap<String, JobRecord> jobs = new LinkedHashMap<>();
    /** Jobs whose manifest could not be read: no record to run, but the job stays on the list until the owner fails it. */
    private final Map<String, ConstructionJob> brokenWithoutManifest = new HashMap<>();
    private long admissions;
    private boolean slowed;
    /**
     * The record whose executor run is on the stack right now. {@link WriteAheadLog.Barrier#at(String)} carries only a
     * point name, so the development barrier reads the running job's kind here; empty outside {@link #place}'s run.
     */
    private JobRecord placing;

    public JobService(BudgetConfig budgetConfig, ClaimBook claims, PartTypeRegistry registry, boolean fastStructure,
                      boolean continueWhileOffline) {
        this(budgetConfig, claims, registry, fastStructure, continueWhileOffline, WriteAheadLog.inMemory());
    }

    public JobService(BudgetConfig budgetConfig, ClaimBook claims, PartTypeRegistry registry, boolean fastStructure,
                      boolean continueWhileOffline, WriteAheadLog wal) {
        this.wal = wal;
        this.budgetConfig = budgetConfig;
        this.budget = new ConstructionBudget(budgetConfig);
        this.claims = claims;
        this.registry = registry;
        this.fastStructure = fastStructure;
        this.continueWhileOffline = continueWhileOffline;
    }

    public void admitApproved(ConstructionJob job, PlacementManifest manifest, Map<String, String> nodeTypes, JobProgram program,
                              Box operatingBox) {
        admitApproved(job, manifest, nodeTypes, program, operatingBox, List.of());
    }

    /** {@code knownConflicts}: positions left alone from the start (a MODIFY's changes the project never placed, Task 29). */
    public void admitApproved(ConstructionJob job, PlacementManifest manifest, Map<String, String> nodeTypes, JobProgram program,
                              Box operatingBox, List<Conflict> knownConflicts) {
        if (job.state() != JobState.PENDING_APPROVAL) {
            throw new IllegalArgumentException("only a freshly approved job can be admitted: " + job.state());
        }
        Journal journal = new Journal(journalCapacity(program.size()));
        JobOutcome outcome = new JobOutcome();
        for (Conflict c : knownConflicts) {
            // kept for good: never resolved by a later placement, counted by status, and it makes the job PARTIAL
            outcome.addRestoreConflict(c);
        }
        add(new JobRecord(job.withTotal(program.size()), manifest, nodeTypes, program, journal, outcome, operatingBox));
    }

    public void add(JobRecord r) {
        if (jobs.putIfAbsent(r.job().jobId(), r) != null) {
            throw new IllegalStateException("job " + r.job().jobId() + " exists already");
        }
    }

    /**
     * A job read back from its files at start-up (04 F-2). The record goes in carrying the job the planner decided
     * on (a job that was moving is re-paused by it), with the pending part of the log its file kept, marked for the
     * {@link #recoverAll} fold when {@code fromLog}, and its saved ledger put back under its job id.
     */
    public void addLoaded(ConstructionJob job, JobLoad.Loaded load, boolean fromLog) {
        JobRecord loaded = load.record();
        JobRecord r = loaded.job().equals(job) ? loaded
                : new JobRecord(job, loaded.manifest(), loaded.nodeTypes(), loaded.program(), loaded.journal(),
                        loaded.outcome(), loaded.operatingBox());
        if (r != loaded) {
            r.pendingLog = loaded.pendingLog;
        }
        if (fromLog) {
            r.requireRecovery();
        }
        ledgers.put(job.jobId(), load.ledger());
        add(r);
    }

    public List<JobUpdate> tick(TickInput in, JobWorld w) {
        List<JobUpdate> updates = new ArrayList<>();
        admitClaims(in, updates);
        presence(in, w, updates);
        start(updates);
        List<JobRecord> placing = inState(PLACING);
        BudgetTick bt = budget.allocate(in.tick(), in.averageMspt(), placing.stream().map(this::budgetJob).toList());
        slowed = bt.slowed();
        Map<String, Integer> allowance = new HashMap<>();
        for (Allowance a : bt.allowances()) {
            allowance.merge(a.jobId(), a.placements(), Integer::sum);
        }
        for (JobRecord r : placing) {
            place(r, allowance.getOrDefault(r.job().jobId(), 0), in, w, updates);
        }
        for (JobRecord r : inState(EnumSet.of(JobState.VERIFYING))) {
            verify(r, in, w, updates);
        }
        return updates;
    }

    private void admitClaims(TickInput in, List<JobUpdate> updates) {
        for (JobRecord r : inState(EnumSet.of(JobState.PENDING_APPROVAL))) {
            ConstructionJob j = r.job();
            boolean build = j.kind() == JobKind.BUILD;
            // MODIFY, REPAIR and ROLLBACK work on an existing claim: it must be live, here, and the job owner's
            List<Issue> issues = new ArrayList<>(build ? List.of() : claims.checkOwned(j.claimId(), j.ownerUuid(), j.dimension()));
            if (issues.isEmpty()) {
                issues.addAll(claims.check(j.ownerUuid(), j.dimension(), r.operatingBox(), build ? null : j.claimId()));
            }
            if (!issues.isEmpty()) {
                change(r, j.withLastError(issues.get(0).id()).on(JobEvent.CLAIM_REFUSED), updates);
                continue;
            }
            if (build) {
                claims.reserve(j.claimId(), j.ownerUuid(), j.dimension(), r.manifest().worldBounds(), r.operatingBox(), in.tick());
            } else if (j.kind() == JobKind.MODIFY) {
                claims.grow(j.claimId(), r.manifest().worldBounds(), r.operatingBox());
            }
            change(r, j.on(JobEvent.ADMITTED), updates);
        }
    }

    private void presence(TickInput in, JobWorld w, List<JobUpdate> updates) {
        for (JobRecord r : List.copyOf(jobs.values())) {
            ConstructionJob j = r.job();
            // offline work needs both the setting and the adapter's yes for this job (its chunks held, an actor to place)
            boolean present = w.ownerOnline(j.ownerUuid()) || (continueWhileOffline && w.mayRunWithoutOwner(j));
            if (!present && AWAY_PAUSES.contains(j.state())) {
                pause(r, PauseReason.OWNER_OFFLINE, in, updates, null);
            } else if (present && j.state() == JobState.PAUSED) {
                boolean retry = RETRIED.contains(j.pauseReason()) && in.tick() - r.pausedAtTick >= RETRY_INTERVAL_TICKS;
                if (j.pauseReason() == PauseReason.OWNER_OFFLINE || retry) {
                    change(r, j.on(JobEvent.RESUME), updates);
                }
            }
        }
    }

    private void start(List<JobUpdate> updates) {
        List<QueuedJob> queued = inState(EnumSet.of(JobState.QUEUED)).stream()
                .map(r -> new QueuedJob(r.job().jobId(), r.job().ownerUuid())).toList();
        List<BudgetJob> running = inState(ACTIVE).stream().map(this::budgetJob).toList();
        for (String id : budget.admit(queued, running)) {
            JobRecord r = jobs.get(id);
            r.admittedOrder = admissions++;
            change(r, r.job().on(JobEvent.START), updates);
        }
    }

    private BudgetJob budgetJob(JobRecord r) {
        return new BudgetJob(r.job().jobId(), r.job().ownerUuid(), fastPhase(r),
                ConstructionBudget.droneCount(r.program().size(), budgetConfig), r.lastDroneTick, r.admittedOrder);
    }

    private boolean fastPhase(JobRecord r) {
        if (!fastStructure || r.repair != null) {
            return false;
        }
        int c = r.job().cursor();
        if (c >= r.program().size() || r.program().isRestore(c)) {
            return true;
        }
        return FAST_PHASES.contains(r.program().put(c).placement().phase());
    }

    private void place(JobRecord r, int allowance, TickInput in, JobWorld w, List<JobUpdate> updates) {
        ConstructionJob j = r.job();
        // the repair queue outlives a pause: PAUSED -> QUEUED -> RUNNING goes on with it instead of the finished main program
        boolean repairing = r.repair != null;
        JobProgram program = repairing ? r.repair.program() : r.program();
        int cursor = repairing ? r.repair.cursor() : j.cursor();
        if (cursor >= program.size()) {
            finishPlacing(r, updates);
            return;
        }
        if (allowance == 0) {
            return;
        }
        // the allowance was sized for the pace at the cursor: stop where the pace changes and re-budget next tick
        int steps = repairing ? allowance : samePaceSteps(program, cursor, allowance, fastStructure);
        ExecutionContext ctx = new ExecutionContext(j, program, w.world(j.dimension()),
                w.materials(j.ownerUuid(), j.materialPolicy(), j.claimId()), r.journal(), ledgers, registry(j.claimId()),
                r.outcome(), r.skipSiteChanges, wal);
        placing = r;
        StepReport rep;
        try {
            rep = ConstructionExecutor.run(ctx, cursor, steps);
        } finally {
            placing = null;
        }
        r.lastRun = wal.lastRun();
        if (repairing) {
            r.repair = new RepairQueue(program, rep.cursor());
        } else {
            r.setJob(j.withCursor(rep.cursor()));
        }
        if (!rep.touched().isEmpty()) {
            r.lastDroneTick = in.tick();
        }
        if (rep.pause() != null) {
            if (rep.pause() == PauseReason.SITE_CHANGED && !program.isRestore(rep.cursor())) {
                r.setJob(r.job().withLastError(siteChanged(program.put(rep.cursor()).placement())));
            }
            pause(r, rep.pause(), in, updates, rep);
            return;
        }
        updates.add(new JobUpdate(r.job(), rep.touched(), List.of(), rep.conflicts(), false));
        if (rep.cursor() >= program.size()) {
            finishPlacing(r, updates);
        }
    }

    /** F-3: the pause names its cause as an Issue id (E-SITE-CHANGED:node#...), which the owner's text is built from. */
    static String siteChanged(Placement p) {
        IntPos pos = p.pos();
        return Issue.of(IssueCode.E_SITE_CHANGED, List.of(p.partNodeId()),
                "位置" + pos.x() + "," + pos.y() + "," + pos.z() + "が、調べた後で変わりました").id();
    }

    /** Program steps from {@code cursor}, at most {@code max}, before the pace changes between fast and drone-paced. */
    static int samePaceSteps(JobProgram program, int cursor, int max, boolean fastStructure) {
        if (!fastStructure) {
            return max;
        }
        boolean fast = fastAt(program, cursor);
        int n = 0;
        while (n < max && cursor + n < program.size() && fastAt(program, cursor + n) == fast) {
            n++;
        }
        return n;
    }

    private static boolean fastAt(JobProgram program, int i) {
        return program.isRestore(i) || FAST_PHASES.contains(program.put(i).placement().phase());
    }

    private void finishPlacing(JobRecord r, List<JobUpdate> updates) {
        r.repair = null;
        r.placementDeviations.clear();
        r.restoreFailures.clear();
        r.collector = new SnapshotCollector(verifyPositions(r));
        // a repair resumed after a pause runs in RUNNING; RUNNING -> VERIFYING is PLACED_ALL and keeps the round
        change(r, r.job().on(r.job().state() == JobState.REPAIRING ? JobEvent.REPAIRED : JobEvent.PLACED_ALL), updates);
    }

    private static List<IntPos> verifyPositions(JobRecord r) {
        List<IntPos> out = new ArrayList<>();
        if (r.job().kind() != JobKind.ROLLBACK) {
            for (Placement p : r.manifest().placements()) {
                out.add(p.pos());
            }
        }
        for (RestoreItem ri : r.program().restores()) {
            out.add(ri.pos());
        }
        return out;
    }

    private void verify(JobRecord r, TickInput in, JobWorld w, List<JobUpdate> updates) {
        if (r.collector == null) {
            r.collector = new SnapshotCollector(verifyPositions(r));
        }
        WorldPort world = w.world(r.job().dimension());
        for (IntPos p : r.collector.nextBatch(SnapshotCollector.DEFAULT_READS_PER_TICK)) {
            WorldCell c = world.read(p);
            if (c.loaded()) {
                r.collector.accept(p, c.observed());
            } else {
                r.collector.unloaded(p);
            }
        }
        if (!r.collector.unloadedPositions().isEmpty()) {
            r.collector = null;
            pause(r, PauseReason.CHUNK_UNLOADED, in, updates, null);
            return;
        }
        while (r.collector.windowFull() && !r.collector.done()) {
            verifyWindow(r, r.collector.takeWindow());
        }
        if (r.collector.done()) {
            // a job ends (or goes on to a repair round) only when a durable point covers its runs: a crash after the end
            // must not find the job finished while the world lost its last writes (the runtime makes the checkpoint)
            if (wal.durableUpTo() < r.lastRun) {
                r.awaitingDurable = true;
                return;
            }
            r.awaitingDurable = false;
            finishVerification(r, updates);
        }
    }

    /** A job waits for a durable point before it may end: the runtime should make a checkpoint (a flushed save) soon. */
    public boolean checkpointWanted() {
        return jobs.values().stream().anyMatch(r -> r.awaitingDurable);
    }

    /** The runtime made a flushed save of the whole server; every run so far is on the disk. */
    public void markDurable() {
        wal.markDurable(wal.lastRun());
    }

    private void verifyWindow(JobRecord r, SnapshotCollector.Window win) {
        int placements = r.job().kind() == JobKind.ROLLBACK ? 0 : r.manifest().placements().size();
        if (win.fromIndex() < placements) {
            r.placementDeviations.addAll(SnapshotDiff.compare(r.manifest(), win.snapshot(),
                    new CompareScope.IndexRange(win.fromIndex(), Math.min(win.toIndexExclusive(), placements)),
                    VolatileProps.of(r.nodeTypes(), registry),
                    registry(r.job().claimId()).assemblies().keySet()).deviations());
        }
        Set<IntPos> conflicted = new HashSet<>();
        for (Conflict c : r.outcome().conflicts()) {
            conflicted.add(c.pos());
        }
        for (int i = Math.max(win.fromIndex(), placements); i < win.toIndexExclusive(); i++) {
            RestoreItem ri = r.program().restore(i - placements);
            ObservedBlock o = win.snapshot().blocks().get(ri.pos());
            if (o != null && !BlockMatch.satisfies(o.block(), ri.restoreTo(), Set.of()) && !conflicted.contains(ri.pos())) {
                r.restoreFailures.add(i - placements);
            }
        }
    }

    private void finishVerification(JobRecord r, List<JobUpdate> updates) {
        ConstructionJob j = r.job();
        PlacedRegistry reg = registry(j.claimId());
        RepairPlan plan = j.kind() == JobKind.ROLLBACK ? new RepairPlan(List.of(), List.of(), List.of())
                : RepairPlanner.plan(r.manifest(), r.placementDeviations, i -> r.beforeJournal().at(i).map(JournalRecord::before),
                        reg::contains, r.outcome().deniedIndexes(), VolatileProps.of(r.nodeTypes(), registry));
        List<Conflict> fresh = new ArrayList<>();
        for (Conflict c : plan.conflicts()) {
            if (r.outcome().addConflict(c)) {
                fresh.add(c);
            }
        }
        List<RestoreItem> retry = new ArrayList<>();
        for (int i : r.restoreFailures) {
            retry.add(r.program().restore(i));
        }
        r.collector = null;
        r.placementDeviations.clear();
        r.restoreFailures.clear();
        // VERIFIED means no deviation at all: a conflict left alone (a player's block, a removal not done) is PARTIAL
        int conflicts = r.outcome().conflicts().size();
        boolean clean = plan.reapply().isEmpty() && plan.unfixable().isEmpty() && retry.isEmpty() && conflicts == 0;
        if (clean) {
            change(r, j.withLastError("").on(JobEvent.CLEAN), updates, fresh);
            if (j.kind() == JobKind.ROLLBACK) {
                finishRollback(r, updates);
            }
            return;
        }
        boolean fixable = !plan.reapply().isEmpty() || !retry.isEmpty();
        // a job recovered in the middle of a repair round re-derives what the round lost from the world and goes on in
        // the same round: the round's ledger keys then charge nothing twice (Task 24)
        boolean sameRound = r.recoveredRound && j.repairRound() > 0;
        r.recoveredRound = false;
        if (fixable && (sameRound || j.repairRound() < ConstructionJob.MAX_REPAIR_ROUNDS)) {
            int round = sameRound ? j.repairRound() : j.repairRound() + 1;
            r.repair = new RepairQueue(new JobProgram(retry,
                    JobProgram.repair(r.manifest(), plan.reapply(), round, plan.reapplyBlocks()).puts()), 0);
            change(r, j.withRepairRound(round).on(JobEvent.NEED_REPAIR), updates, fresh);
            return;
        }
        r.remaining.clear();
        r.remaining.addAll(plan.unfixable());
        for (int i : plan.reapply()) {
            Placement p = r.manifest().placements().get(i);
            r.remaining.add(new Deviation(i, p.block(), new ObservedBlock(BlockSpec.AIR),
                    DeviationKind.MISSING));
        }
        change(r, j.withLastError(summary(r.remaining, retry.size(), conflicts)).on(JobEvent.GIVE_UP), updates, fresh);
    }

    /** A machine-readable reason for PARTIAL, e.g. "missing=1 blocked=2 conflict=1"; the owner's text is built from it. */
    static String summary(List<Deviation> remaining, int restoreFailures, int conflicts) {
        Map<String, Integer> byKind = new TreeMap<>();
        for (Deviation d : remaining) {
            byKind.merge(d.kind().name().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        byKind.forEach((k, n) -> sb.append(sb.isEmpty() ? "" : " ").append(k).append('=').append(n));
        if (restoreFailures > 0) {
            sb.append(sb.isEmpty() ? "" : " ").append("restore=").append(restoreFailures);
        }
        if (conflicts > 0) {
            sb.append(sb.isEmpty() ? "" : " ").append("conflict=").append(conflicts);
        }
        return sb.toString();
    }

    private void finishRollback(JobRecord rollback, List<JobUpdate> updates) {
        String claimId = rollback.job().claimId();
        for (JobRecord r : jobsInClaim(claimId)) {
            if (r != rollback && ROLLBACKABLE.contains(r.job().state())) {
                change(r, r.job().on(JobEvent.ROLL_BACK_DONE), updates);
            }
        }
        claims.release(claimId);
        registries.remove(claimId);
    }

    private void pause(JobRecord r, PauseReason reason, TickInput in, List<JobUpdate> updates, StepReport rep) {
        r.pausedAtTick = in.tick();
        ConstructionJob next = r.job().paused(reason);
        r.setJob(next);
        updates.add(new JobUpdate(next, rep == null ? List.of() : rep.touched(), rep == null ? List.of() : rep.shortage(),
                rep == null ? List.of() : rep.conflicts(), true));
    }

    private void change(JobRecord r, ConstructionJob next, List<JobUpdate> updates) {
        change(r, next, updates, List.of());
    }

    private void change(JobRecord r, ConstructionJob next, List<JobUpdate> updates, List<Conflict> conflicts) {
        boolean moved = r.job().state() != next.state();
        r.setJob(next);
        updates.add(new JobUpdate(next, List.of(), List.of(), conflicts, moved));
    }

    public ControlResult cancel(String jobId, UUID requester, boolean op) {
        JobRecord r = jobs.get(jobId);
        if (r == null) {
            return ControlResult.NOT_FOUND;
        }
        if (!r.job().ownerUuid().equals(requester) && !op) {
            return ControlResult.NOT_ALLOWED;
        }
        if (!JobStateMachine.allows(r.job().state(), JobEvent.CANCEL)) {
            return ControlResult.WRONG_STATE;
        }
        r.collector = null;
        r.repair = null;
        r.setJob(r.job().on(JobEvent.CANCEL));
        return ControlResult.OK;
    }

    public ControlResult resume(String jobId, UUID requester, boolean op, boolean skipSiteChanges) {
        JobRecord r = jobs.get(jobId);
        if (r == null) {
            return ControlResult.NOT_FOUND;
        }
        if (!r.job().ownerUuid().equals(requester) && !op) {
            return ControlResult.NOT_ALLOWED;
        }
        if (r.job().state() != JobState.PAUSED || r.job().pauseReason() == PauseReason.RECOVERY_NEEDED) {
            return ControlResult.WRONG_STATE;
        }
        r.skipSiteChanges = skipSiteChanges;
        r.setJob(r.job().on(JobEvent.RESUME));
        return ControlResult.OK;
    }

    /**
     * The owner's ask to re-check a finished job (03 0.4: a REPAIR needs no approval): allowed only while the parent
     * is VERIFIED or PARTIAL and no job still moves on its claim. The new job gets its own journal — a re-placement
     * of a position must not reuse the parent's record of that same position — and reads the pre-build blocks from
     * the parent's journal ({@link JobRecord#useBeforesFrom}).
     */
    public ControlResult beginVerify(String jobId, UUID requester, boolean op, String newJobId, long tick) {
        JobRecord parent = jobs.get(jobId);
        if (parent == null) {
            return ControlResult.NOT_FOUND;
        }
        ConstructionJob p = parent.job();
        if (!p.ownerUuid().equals(requester) && !op) {
            return ControlResult.NOT_ALLOWED;
        }
        boolean finished = p.state() == JobState.VERIFIED || p.state() == JobState.PARTIAL;
        boolean busyClaim = jobsInClaim(p.claimId()).stream().anyMatch(r -> !r.job().state().terminal());
        if (!finished || busyClaim || claimAwaitsAnswer(p.claimId())) {
            return ControlResult.WRONG_STATE;
        }
        ConstructionJob job = ConstructionJob.create(newJobId, p.ownerUuid(), p.dimension(), p.manifestHash(),
                JobKind.REPAIR, p.jobId(), 0, p.claimId(), p.materialPolicy(), tick, List.of());
        JobRecord repair = new JobRecord(job, parent.manifest(), parent.nodeTypes(), new JobProgram(List.of(), List.of()),
                new Journal(journalCapacity(parent.program().size())), new JobOutcome(), parent.operatingBox());
        repair.useBeforesFrom(parent.journal());
        add(repair);
        return ControlResult.OK;
    }

    /** Every repair round journals its own placements (keyed by ledger key), so room for all rounds. */
    static int journalCapacity(int programSize) {
        return Math.max(Journal.MAX_ENTRIES, programSize) * (1 + ConstructionJob.MAX_REPAIR_ROUNDS);
    }

    public Optional<JobStatus> status(String jobId) {
        JobRecord r = jobs.get(jobId);
        if (r != null) {
            return Optional.of(statusOf(r));
        }
        ConstructionJob lone = brokenWithoutManifest.get(jobId);
        return lone == null ? Optional.empty() : Optional.of(statusOfLone(lone));
    }

    public List<JobStatus> statuses() {
        List<JobStatus> out = new ArrayList<>();
        jobs.values().forEach(r -> out.add(statusOf(r)));
        brokenWithoutManifest.values().forEach(j -> out.add(statusOfLone(j)));
        return List.copyOf(out);
    }

    /** A broken job without a manifest has no record: its status is the job alone. */
    private static JobStatus statusOfLone(ConstructionJob j) {
        return new JobStatus(j.jobId(), j.ownerUuid(), j.kind(), j.state(), j.pauseReason(), j.cursor(), j.total(),
                j.repairRound(), 0, 0, 0, j.lastError(), j.claimId(), j.dimension());
    }

    private JobStatus statusOf(JobRecord r) {
        ConstructionJob j = r.job();
        PauseReason shown = j.pauseReason();
        if (j.state() == JobState.QUEUED || (j.state() == JobState.RUNNING && slowed)) {
            shown = j.state() == JobState.QUEUED && inState(ACTIVE).isEmpty() ? null : PauseReason.SERVER_BUSY;
        }
        return new JobStatus(j.jobId(), j.ownerUuid(), j.kind(), j.state(), shown, j.cursor(), j.total(), j.repairRound(),
                r.outcome().conflicts().size(), r.outcome().skipped().size(), unrepaired(r), j.lastError(),
                j.claimId(), j.dimension());
    }

    /**
     * The positions the last verification gave up on, minus the ones already counted elsewhere: a skipped
     * placement keeps its own count, and a position named by a live conflict is not counted a second time.
     * With skipped and conflicts this makes the PARTIAL count agree with {@code lastError}.
     */
    private static int unrepaired(JobRecord r) {
        if (r.remaining.isEmpty()) {
            return 0;
        }
        Set<IntPos> conflicted = new HashSet<>();
        for (Conflict c : r.outcome().conflicts()) {
            conflicted.add(c.pos());
        }
        int n = 0;
        for (Deviation d : r.remaining) {
            if (r.outcome().isSkipped(d.placementIndex())
                    || conflicted.contains(r.manifest().placements().get(d.placementIndex()).pos())) {
                continue;
            }
            n++;
        }
        return n;
    }

    public Optional<JobRecord> record(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    public List<JobRecord> records() {
        return List.copyOf(jobs.values());
    }

    /**
     * The job whose executor run is on the stack right now, for the development barrier's per-kind arming. Empty
     * between runs, in tests, and while the service is doing anything but placing.
     */
    public Optional<ConstructionJob> placingJob() {
        JobRecord r = placing;
        return Optional.ofNullable(r == null ? null : r.job());
    }

    public List<JobRecord> jobsInClaim(String claimId) {
        return jobs.values().stream().filter(r -> r.job().claimId().equals(claimId)).toList();
    }

    public PlacedRegistry registry(String claimId) {
        return registries.computeIfAbsent(claimId, PlacedRegistry::new);
    }

    public Map<String, PlacedRegistry> registries() {
        return registries;
    }

    public LedgerBook ledgers() {
        return ledgers;
    }

    public ClaimBook claims() {
        return claims;
    }

    public boolean slowed() {
        return slowed;
    }

    /**
     * What start-up recovery found: for each job that still waits for its owner's answer, the part of the write-ahead
     * log it kept (pendingLogs), and one line per ambiguous job saying why (reasons).
     */
    public record RecoveryReport(Map<String, List<WalEntry>> pendingLogs, List<String> reasons) {
        public RecoveryReport {
            TreeMap<String, List<WalEntry>> ordered = new TreeMap<>(pendingLogs);
            ordered.replaceAll((k, v) -> List.copyOf(v));
            pendingLogs = Collections.unmodifiableMap(ordered);
            reasons = List.copyOf(reasons);
        }
    }

    /**
     * Start-up recovery (04 F-2): runs once, synchronously, before the first tick. The durable log is read once and every
     * job marked {@link JobRecord#recoveryPending()} is folded against it. An ambiguous job keeps its part of the log
     * ({@code pending_log.bin}) and waits for the owner's answer — never silently adopted or discarded; a decided job is
     * folded into its journal, registry and ledgers, and its cursor rewound so the re-walk places again what the world
     * lost without charging twice. The runtime saves the files afterwards and checkpoints the log ({@link #markDurable}).
     */
    public RecoveryReport recoverAll(JobWorld w) {
        List<WalEntry> log = null;
        Map<String, List<WalEntry>> pending = new TreeMap<>();
        List<String> reasons = new ArrayList<>();
        for (JobRecord r : List.copyOf(jobs.values())) {
            if (!r.recoveryPending) {
                continue;
            }
            if (log == null) {
                log = wal.durable();
            }
            WalRecovery.Result x = recoverOne(r, log, w, WalRecovery.Resolution.NONE);
            if (x.ambiguous()) {
                pending.put(r.job().jobId(), r.pendingLog);
                reasons.add(r.job().jobId() + " " + x.reasons());
            }
        }
        return new RecoveryReport(pending, reasons);
    }

    /** Folds one job's log part in; an ambiguous job is parked for its owner's answer (its part is kept). */
    private WalRecovery.Result recoverOne(JobRecord r, List<WalEntry> log, JobWorld w, WalRecovery.Resolution answer) {
        ConstructionJob j = r.job();
        long savedTx = w.materials(j.ownerUuid(), j.materialPolicy(), j.claimId()).durableTx();
        WalRecovery.Result x = WalRecovery.recover(j.jobId(), log, r.program(), r.journal(), registry(j.claimId()), ledgers,
                w.world(j.dimension()), savedTx, answer);
        r.recoveryPending = false;
        if (x.ambiguous()) {
            r.pendingLog = WalRecovery.pendingPart(j.jobId(), log);
            r.setJob(j.withLastError(RECOVERY_AMBIGUOUS + String.join(",", x.reasons())));
            if (!j.state().terminal()) {
                pauseForRecovery(r);
            }
            return x;
        }
        r.pendingLog = List.of();
        r.possiblyLostDrops = x.possiblyLostDrops();
        if (!j.state().terminal()) {
            if (!RecoveryPlanner.settledUpTo(r, j.cursor(), x.explained())) {
                // positions before the cursor that neither the files nor the log account for: never re-run silently
                pauseForRecovery(r);
                return x;
            }
            if (x.rewindTo() < j.cursor()) {
                r.setJob(r.job().withCursor(x.rewindTo()));
            }
            // a crash inside a repair round is re-derived in the same round, so the round's ledger keys charge once
            r.recoveredRound = j.repairRound() > 0;
        }
        return x;
    }

    private static void pauseForRecovery(JobRecord r) {
        ConstructionJob j = r.job();
        if (j.state() == JobState.PAUSED && j.pauseReason() == PauseReason.RECOVERY_NEEDED) {
            return;
        }
        r.setJob((j.state() == JobState.PAUSED ? j.on(JobEvent.RESUME) : j).paused(PauseReason.RECOVERY_NEEDED));
    }

    /** Whether this job waits for its owner's ADOPT or DISCARD answer on part of the log. */
    public boolean awaitsAnswer(String jobId) {
        JobRecord r = jobs.get(jobId);
        return r != null && !r.pendingLog.isEmpty();
    }

    /** Whether any job on the claim still waits for an answer: such a claim takes no new work (Task 21, 28, 29 gates). */
    public boolean claimAwaitsAnswer(String claimId) {
        return jobsInClaim(claimId).stream().anyMatch(r -> !r.pendingLog.isEmpty());
    }

    /**
     * The owner's (or an operator's) answer for a recovery-paused job: ADOPT or DISCARD decides the waiting part of the
     * log; REPAIR and FAIL end the job (a waiting part is discarded first — it was never proven), and REPAIR starts a
     * REPAIR job that re-derives everything from the world, taking the pre-build blocks from the journal unless the
     * files were broken.
     */
    public ControlResult recover(String jobId, UUID requester, boolean op, RecoveryChoice choice, String newJobId,
                                 long tick, JobWorld w) {
        JobRecord r = jobs.get(jobId);
        if (r == null) {
            return ControlResult.NOT_FOUND;
        }
        ConstructionJob j = r.job();
        if (!j.ownerUuid().equals(requester) && !op) {
            return ControlResult.NOT_ALLOWED;
        }
        boolean waiting = awaitsAnswer(jobId);
        boolean recoveryPaused = j.state() == JobState.PAUSED && j.pauseReason() == PauseReason.RECOVERY_NEEDED;
        if (!recoveryPaused && !waiting) {
            return ControlResult.WRONG_STATE;
        }
        if (choice == RecoveryChoice.ADOPT || choice == RecoveryChoice.DISCARD) {
            if (!waiting) {
                return ControlResult.WRONG_STATE;
            }
            // a finished job keeps its state: the answer only settles the log part, so its claim unblocks
            return answer(r, choice, w);
        }
        if (!recoveryPaused) {
            return ControlResult.WRONG_STATE;
        }
        if (waiting) {
            answer(r, RecoveryChoice.DISCARD, w);
        }
        r.setJob(r.job().on(JobEvent.FAIL));
        if (choice == RecoveryChoice.REPAIR) {
            ConstructionJob job = ConstructionJob.create(newJobId, j.ownerUuid(), j.dimension(), j.manifestHash(),
                    JobKind.REPAIR, j.jobId(), 0, j.claimId(), j.materialPolicy(), tick, List.of());
            JobRecord repair = new JobRecord(job, r.manifest(), r.nodeTypes(), new JobProgram(List.of(), List.of()),
                    new Journal(journalCapacity(r.program().size())), new JobOutcome(), r.operatingBox());
            if (!r.broken) {
                repair.useBeforesFrom(r.journal());
            }
            add(repair);
        }
        return ControlResult.OK;
    }

    /** Folds the waiting part in under the owner's answer; the job resumes when its journal is settled again. */
    private ControlResult answer(JobRecord r, RecoveryChoice choice, JobWorld w) {
        WalRecovery.Resolution res = choice == RecoveryChoice.ADOPT ? WalRecovery.Resolution.ADOPT
                : WalRecovery.Resolution.DISCARD;
        WalRecovery.Result x = recoverOne(r, r.pendingLog, w, res);
        if (x.ambiguous()) {
            return ControlResult.WRONG_STATE;
        }
        ConstructionJob j = r.job();
        r.setJob(j.withLastError(""));
        if (j.state() == JobState.PAUSED && j.pauseReason() == PauseReason.RECOVERY_NEEDED
                && RecoveryPlanner.settledUpTo(r, j.cursor())) {
            r.setJob(r.job().on(JobEvent.RESUME));
        }
        return ControlResult.OK;
    }

    /**
     * A job whose files could not be read (04 F-2): kept for the owner's decision, never run silently. Without a
     * manifest no record can be built (the program and the operating box both need it); the bare job is kept so it is
     * still listed and can be failed.
     */
    public void addBroken(ConstructionJob job, PlacementManifest manifestOrNull, Map<String, String> nodeTypes,
                          Box operatingBox, List<String> reasons) {
        ConstructionJob shown = job.withLastError(String.join("; ", reasons));
        if (manifestOrNull == null) {
            brokenWithoutManifest.put(shown.jobId(), shown);
            return;
        }
        JobProgram program = job.kind() == JobKind.BUILD ? JobProgram.build(manifestOrNull)
                : new JobProgram(List.of(), List.of());
        JobRecord r = new JobRecord(shown, manifestOrNull, nodeTypes, program,
                new Journal(journalCapacity(program.size())), new JobOutcome(), operatingBox);
        r.broken = true;
        add(r);
    }

    private List<JobRecord> inState(Set<JobState> states) {
        return jobs.values().stream().filter(r -> states.contains(r.job().state())).toList();
    }
}
