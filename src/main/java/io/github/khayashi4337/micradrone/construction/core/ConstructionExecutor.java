package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.BlockMatch;
import io.github.khayashi4337.micradrone.build.compile.BlockToItem;
import io.github.khayashi4337.micradrone.build.compile.Conflict;
import io.github.khayashi4337.micradrone.build.compile.ConflictKind;
import io.github.khayashi4337.micradrone.build.compile.Conflicts;
import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Runs up to {@code allowance} steps of a job program from {@code cursor} (04 F-2, F-5, F-7). Every step reads the
 * world first and decides with the same rules as the approval did, so a re-run from an older cursor is harmless:
 * journaled positions that already hold their block are skipped, and the ledger is settled to what is still owed, never
 * more. One call is one run of the write-ahead log: intents are durable before the first change, the run's end right
 * after the last one (a run that changed nothing still ends on the log, or recovery would read it as cut short), then
 * the owner's inventory is saved with the run as its transaction id. Nothing is ever dropped inside a material
 * operation: a give that does not fit pauses (NO_ROOM) before the world is touched.
 */
public final class ConstructionExecutor {
    private record Step(PauseReason pause, List<ItemCount> shortage) {
        static final Step NEXT = new Step(null, List.of());

        static Step pause(PauseReason reason) {
            return new Step(reason, List.of());
        }
    }

    /** What one run really did, for its RunEnd entry. */
    private static final class RunLog {
        final long run;
        final List<Integer> written = new ArrayList<>();
        final List<WalEntry.OpMoves> moves = new ArrayList<>();
        final Set<IntPos> restored = new HashSet<>();
        boolean movedInventory;

        RunLog(long run) {
            this.run = run;
        }
    }

    private ConstructionExecutor() {
    }

    public static StepReport run(ExecutionContext ctx, int cursor, int allowance) {
        WriteAheadLog wal = ctx.wal();
        String jobId = ctx.job().jobId();
        long run = wal.newRun();
        List<WalEntry> intents = intents(ctx, cursor, allowance, run);
        boolean started = !intents.isEmpty();
        if (started) {
            wal.append(new WalEntry.RunStart(jobId, run));
            intents.forEach(wal::append);
            wal.flush();
            wal.at(WriteAheadLog.Barrier.AFTER_INTENTS);
        }
        RunLog log = new RunLog(run);
        StepReport report = walk(ctx, cursor, allowance, log);
        if (started) {
            // a run that opened the log always writes its end, even one that paused before its first change: a start
            // without an end would look cut short to recovery
            wal.at(WriteAheadLog.Barrier.AFTER_WRITES);
            wal.append(new WalEntry.RunEnd(jobId, run, log.written, log.moves));
            wal.flush();
            if (log.movedInventory) {
                wal.at(WriteAheadLog.Barrier.AFTER_RUN_END);
                // the run number is the transaction id saved with the inventory: recovery reads the id, never counts
                ctx.materials().persist(run);
                wal.at(WriteAheadLog.Barrier.AFTER_PLAYER_SAVE);
            }
        }
        return report;
    }

    /** Whether program position {@code j} restores the other half of the piece restored at {@code j - 1}. */
    static boolean continuesPiece(JobProgram program, int j) {
        return j > 0 && j < program.size() && program.isRestore(j - 1) && program.isRestore(j)
                && Attachments.samePiece(program.restore(j - 1), program.restore(j));
    }

    static Optional<BlockSpec> owned(ExecutionContext ctx, IntPos pos) {
        return ctx.registry().at(pos).map(PlacedEntry::placed);
    }

    private static Optional<BlockSpec> owned(SiteView view, IntPos pos) {
        return view.placedAt(pos).map(PlacedEntry::placed);
    }

    /**
     * The registry entry a put may treat as its own block: only a repair round's step (ledger key of round 1 or later,
     * JobProgram.repair) re-places the project's block of the same id in a wrong state. A first-round step (BUILD,
     * MODIFY) never does: a MODIFY's changed position was checked against what the project placed (its removal, with
     * BlockMatch.satisfies and the volatile states) and a mismatch is a lasting Conflict, not something to overwrite.
     */
    static Optional<BlockSpec> ownedFor(ExecutionContext ctx, PutItem item) {
        return ownedFor(live(ctx), item);
    }

    private static Optional<BlockSpec> ownedFor(SiteView view, PutItem item) {
        return item.ledgerKey() >= JobProgram.LEDGER_ROUND_STRIDE
                ? owned(view, item.placement().pos()) : Optional.empty();
    }

    /**
     * A step this job did and then built over (a ground cut under the foundation): the registry's entry is this job's
     * later write and lists this step's key as an earlier one. A re-walk from an older cursor counts it as done.
     */
    static boolean superseded(ExecutionContext ctx, PutItem item, Optional<JournalRecord> journaled) {
        return superseded(live(ctx), ctx.job().jobId(), item, journaled);
    }

    private static boolean superseded(SiteView view, String jobId, PutItem item, Optional<JournalRecord> journaled) {
        IntPos pos = item.placement().pos();
        return journaled.isPresent() && view.placedAt(pos)
                .filter(e -> e.jobId().equals(jobId) && e.earlierKeys().contains(item.ledgerKey())
                        && BlockMatch.exact(view.read(pos).block(), e.placed(), Set.of())).isPresent();
    }

    /** A block the assembly (P10/P13) has already moved into its contraption: its position is never written again. */
    static boolean assembledAway(ExecutionContext ctx, Placement p) {
        return p.verify() == VerifyMode.ASSEMBLED_AWAY && p.assemblyGroup() != null
                && ctx.registry().assemblies().containsKey(p.assemblyGroup());
    }

    static List<ItemCount> cost(Placement p) {
        return BlockToItem.cost(p.block()).map(List::of).orElse(List.of());
    }

    static List<ItemCount> ground(BlockSpec cut) {
        return BlockToItem.cutYield(cut).map(List::of).orElse(List.of());
    }

    private static boolean survival(ExecutionContext ctx) {
        return ctx.job().materialPolicy() == MaterialPolicy.SURVIVAL_CONSUME;
    }

    /**
     * What a pass of one run reads. The walk reads the real files; the intents pass reads them under an overlay of what
     * the run's earlier steps will have written (a ground cut under a foundation at the same position), so a later
     * step's intent names the block it will really find, not the pre-run one.
     */
    private interface SiteView {
        WorldCell read(IntPos pos);

        Optional<JournalRecord> journaled(int key);

        Optional<PlacedEntry> placedAt(IntPos pos);

        boolean hasRestoreConflictAt(IntPos pos);
    }

    private static SiteView live(ExecutionContext ctx) {
        return new SiteView() {
            @Override
            public WorldCell read(IntPos pos) {
                return ctx.world().read(pos);
            }

            @Override
            public Optional<JournalRecord> journaled(int key) {
                return ctx.journal().at(key);
            }

            @Override
            public Optional<PlacedEntry> placedAt(IntPos pos) {
                return ctx.registry().at(pos);
            }

            @Override
            public boolean hasRestoreConflictAt(IntPos pos) {
                return ctx.outcome().hasRestoreConflictAt(pos);
            }
        };
    }

    /**
     * The intents pass's overlay: a copy of the registry that planned records are applied to (the real apply logic, so
     * earlier keys chain exactly as they will at the write), the cell each planned write leaves behind, the records the
     * run plans to journal, and the positions a planned removal already refused to touch. The real files never change.
     */
    private static final class OverlayView implements SiteView {
        private final SiteView base;
        private final String jobId;
        private final PlacedRegistry registry;
        private final Map<IntPos, WorldCell> cells = new HashMap<>();
        private final Map<Integer, JournalRecord> journalWrites = new HashMap<>();
        private final Set<IntPos> restoreConflicts = new HashSet<>();

        OverlayView(ExecutionContext ctx) {
            this.base = live(ctx);
            this.jobId = ctx.job().jobId();
            this.registry = new PlacedRegistry(ctx.registry().claimId());
            this.registry.putAll(ctx.registry().placed());
        }

        @Override
        public WorldCell read(IntPos pos) {
            WorldCell c = cells.get(pos);
            return c != null ? c : base.read(pos);
        }

        @Override
        public Optional<JournalRecord> journaled(int key) {
            JournalRecord r = journalWrites.get(key);
            return r != null ? Optional.of(r) : base.journaled(key);
        }

        @Override
        public Optional<PlacedEntry> placedAt(IntPos pos) {
            return registry.at(pos);
        }

        @Override
        public boolean hasRestoreConflictAt(IntPos pos) {
            return restoreConflicts.contains(pos) || base.hasRestoreConflictAt(pos);
        }

        /** A step that only records (an already-restored position): the files change, the world does not. */
        void recorded(JournalRecord rec) {
            journalWrites.put(rec.placementIndex(), rec);
            registry.apply(jobId, rec);
        }

        /** A step that writes the world: the cell it leaves is what the run's later steps will read. */
        void wrote(JournalRecord rec) {
            recorded(rec);
            cells.put(rec.pos(), cellAfterWrite(rec));
        }

        void restoreConflicted(IntPos pos) {
            restoreConflicts.add(pos);
        }
    }

    /** The cell a write leaves: the plain block it placed, or replaceable air after a clear. */
    private static WorldCell cellAfterWrite(JournalRecord rec) {
        return rec.placed().isAir() ? WorldCell.of(BlockSpec.AIR, CellTrait.REPLACEABLE) : WorldCell.of(rec.placed());
    }

    /**
     * The read-only first pass: what this run may write and move, logged before anything changes. Each step is judged
     * against the overlay of what the run's earlier steps will have done (the walk decides by the same rules), so two
     * steps at one position — a ground cut, then the foundation on it — get intents named for what is really found.
     */
    static List<WalEntry> intents(ExecutionContext ctx, int cursor, int allowance, long run) {
        List<WalEntry> out = new ArrayList<>();
        JobProgram program = ctx.program();
        String jobId = ctx.job().jobId();
        ctx.ledgers().of(jobId);
        OverlayView view = new OverlayView(ctx);
        int done = 0;
        for (int c = cursor; c < program.size(); c++, done++) {
            if (done >= allowance && !continuesPiece(program, c)) {
                break;
            }
            if (program.isRestore(c)) {
                RestoreItem item = program.restore(c);
                MaterialLedger source = ctx.ledgers().of(item.sourceJobId());
                switch (planRestore(view, ctx, c, item)) {
                    case RestorePlan.Unloaded u -> {
                        return out;
                    }
                    case RestorePlan.AlreadyRestored a -> {
                        if (a.journaled()) {
                            removalIntents(out, jobId, run, item, source);
                        } else {
                            // the walk will only record the position as undone
                            view.recorded(restoreRecord(c, item, view.read(item.pos())));
                        }
                    }
                    case RestorePlan.Conflicted f -> view.restoreConflicted(item.pos());
                    case RestorePlan.Remove m -> {
                        JournalRecord rec = restoreRecord(c, item, m.cell());
                        out.add(new WalEntry.PlaceIntent(jobId, run, rec));
                        if (item.dropContents() && m.cell().observed().hasBlockEntity()) {
                            out.add(new WalEntry.DropIntent(jobId, run, item.pos()));
                        }
                        removalIntents(out, jobId, run, item, source);
                        view.wrote(rec);
                    }
                }
                continue;
            }
            PutItem item = program.put(c);
            int key = item.ledgerKey();
            switch (planPut(view, ctx, item)) {
                case PutPlan.Unloaded u -> {
                    return out;
                }
                case PutPlan.Conflicted f -> {
                }
                case PutPlan.Assembled a ->
                        intend(out, jobId, run, new OpKey(jobId, key, MaterialOp.CHARGE), a.chargeOwed());
                case PutPlan.Settle s -> {
                    intend(out, jobId, run, new OpKey(jobId, key, MaterialOp.CHARGE), s.chargeOwed());
                    intend(out, jobId, run, new OpKey(jobId, key, MaterialOp.YIELD), s.yieldOwed());
                }
                case PutPlan.AlreadyThere a -> {
                }
                case PutPlan.Refused r -> {
                    if (!ctx.skipSiteChanges()) {
                        return out;
                    }
                }
                case PutPlan.Place pl -> {
                    JournalRecord rec = putRecord(item, pl.cell(), pl.terrainCut());
                    out.add(new WalEntry.PlaceIntent(jobId, run, rec));
                    intend(out, jobId, run, new OpKey(jobId, key, MaterialOp.CHARGE), pl.chargeOwed());
                    intend(out, jobId, run, new OpKey(jobId, key, MaterialOp.YIELD), pl.yieldOwed());
                    view.wrote(rec);
                }
            }
        }
        return out;
    }

    /** The material operations one removal still owes its source job's ledger keys (a refund owed is only for what was paid). */
    private static void removalIntents(List<WalEntry> out, String jobId, long run, RestoreItem item, MaterialLedger source) {
        for (int key : item.sourceKeys()) {
            intend(out, jobId, run, new OpKey(item.sourceJobId(), key, MaterialOp.RECLAIM), source.owedReclaim(key));
            intend(out, jobId, run, new OpKey(item.sourceJobId(), key, MaterialOp.CHARGE),
                    ItemCount.minus(source.returned(key), source.consumed(key)));
            intend(out, jobId, run, new OpKey(item.sourceJobId(), key, MaterialOp.RETURN), source.owedReturn(key));
        }
    }

    private static void intend(List<WalEntry> out, String jobId, long run, OpKey key, List<ItemCount> items) {
        if (!items.isEmpty()) {
            out.add(new WalEntry.MaterialIntent(jobId, run, key, items));
        }
    }

    static JournalRecord putRecord(PutItem item, WorldCell cell, boolean cut) {
        Placement p = item.placement();
        // keyed by the ledger key: a repair round's placement of the same index is its own record (JobProgram.repair)
        return new JournalRecord(item.ledgerKey(), p.pos(), cell.block(), cell.observed().hasBlockEntity(), p.block(),
                item.ledgerKey(), cut);
    }

    static JournalRecord restoreRecord(int programIndex, RestoreItem item, WorldCell cell) {
        return new JournalRecord(JournalRecord.restoreIndex(programIndex), item.pos(), cell.block(),
                cell.observed().hasBlockEntity(), item.restoreTo(), JournalRecord.NO_LEDGER_KEY);
    }

    /** What one placement step will do, decided against the pass's view (the intents pass and the walk share it). */
    private sealed interface PutPlan {
        /** The chunk is not loaded: pause. */
        record Unloaded() implements PutPlan {
        }

        /** A removal conflict blocks the position: skip the step. */
        record Conflicted() implements PutPlan {
        }

        /** The assembly took the block into its contraption: at most the charge is still owed. */
        record Assembled(List<ItemCount> chargeOwed) implements PutPlan {
        }

        /** The journaled (or superseded) write already stands: settle what the ledger still owes. */
        record Settle(List<ItemCount> chargeOwed, List<ItemCount> yieldOwed) implements PutPlan {
        }

        /** A repair round's step found the project's block already right: skip, place and charge nothing. */
        record AlreadyThere() implements PutPlan {
        }

        /** The position no longer matches the plan: report a conflict (the walk pauses or skips on it). */
        record Refused(Conflict conflict) implements PutPlan {
        }

        /** The block goes in: write it, journal it, then settle the charge (and the ground a cut hands over). */
        record Place(WorldCell cell, boolean terrainCut, List<ItemCount> chargeOwed, List<ItemCount> yieldOwed)
                implements PutPlan {
        }
    }

    private static PutPlan planPut(SiteView view, ExecutionContext ctx, PutItem item) {
        Placement p = item.placement();
        WorldCell cell = view.read(p.pos());
        if (!cell.loaded()) {
            return new PutPlan.Unloaded();
        }
        if (view.hasRestoreConflictAt(p.pos())) {
            return new PutPlan.Conflicted();
        }
        String jobId = ctx.job().jobId();
        MaterialLedger ledger = ctx.ledgers().of(jobId);
        int key = item.ledgerKey();
        List<ItemCount> chargeOwed = survival(ctx) ? ItemCount.minus(cost(p), ledger.consumed(key)) : List.of();
        if (assembledAway(ctx, p)) {
            return new PutPlan.Assembled(chargeOwed);
        }
        Optional<JournalRecord> journaled = view.journaled(key);
        ReplaceDecision d = superseded(view, jobId, item, journaled) ? new ReplaceDecision.AlreadyDone()
                : ReplaceRules.decide(p, cell, journaled.isPresent(), ownedFor(view, item));
        if (d instanceof ReplaceDecision.AlreadyDone) {
            JournalRecord rec = journaled.orElseThrow();
            // the block is there; settle what the ledger still owes (a crash can leave the charge or the ground unsettled)
            return new PutPlan.Settle(chargeOwed,
                    rec.terrainCut() ? ItemCount.minus(ground(rec.before()), ledger.yielded(key)) : List.of());
        }
        if (journaled.isEmpty() && ownedFor(view, item).isPresent()
                && BlockMatch.satisfies(cell.block(), p.block(), Set.of())) {
            return new PutPlan.AlreadyThere();
        }
        if (d instanceof ReplaceDecision.Refused) {
            return new PutPlan.Refused(new Conflict(p.pos(), p.block(), cell.observed(),
                    cell.block().isAir() ? ConflictKind.MISSING : ConflictKind.PLAYER_MODIFIED));
        }
        Destruction destruction = ((ReplaceDecision.Place) d).destruction();
        boolean cut = survival(ctx) && destruction == Destruction.TERRAIN;
        return new PutPlan.Place(cell, cut, chargeOwed,
                cut ? ItemCount.minus(ground(cell.block()), ledger.yielded(key)) : List.of());
    }

    /** What one removal step will do, decided against the pass's view (the intents pass and the walk share it). */
    private sealed interface RestorePlan {
        /** The chunk is not loaded: pause. */
        record Unloaded() implements RestorePlan {
        }

        /** The position already shows restoreTo: nothing to write. journaled = this job removed it (settle the owed). */
        record AlreadyRestored(boolean journaled) implements RestorePlan {
        }

        /** Not this job's to change: the world differs from expectedNow, or the registry cannot prove ownership. */
        record Conflicted(Conflict conflict) implements RestorePlan {
        }

        /** The block comes out: remove it, journal it, then settle the source keys. */
        record Remove(WorldCell cell) implements RestorePlan {
        }
    }

    private static RestorePlan planRestore(SiteView view, ExecutionContext ctx, int i, RestoreItem item) {
        WorldCell cell = view.read(item.pos());
        if (!cell.loaded()) {
            return new RestorePlan.Unloaded();
        }
        if (BlockMatch.satisfies(cell.block(), item.restoreTo(), Set.of())) {
            return new RestorePlan.AlreadyRestored(view.journaled(JournalRecord.restoreIndex(i)).isPresent());
        }
        Conflict conflict = Conflicts.detect(item.pos(), item.expectedNow(), cell.observed(), item.volatileProps())
                .orElse(null);
        if (conflict == null && !owns(view, item, cell) && !removalRecorded(view, i, item, cell)) {
            // the world shows what the step expected, but the registry cannot prove the source job wrote it
            conflict = new Conflict(item.pos(), item.expectedNow(), cell.observed(), ConflictKind.PLAYER_MODIFIED);
        }
        return conflict != null ? new RestorePlan.Conflicted(conflict) : new RestorePlan.Remove(cell);
    }

    /**
     * A removal this job already journaled is done again when the world still shows the block the record removed (its
     * write was lost with an unsaved chunk while the files kept the record): the record itself is proof enough.
     */
    private static boolean removalRecorded(SiteView view, int i, RestoreItem item, WorldCell cell) {
        return view.journaled(JournalRecord.restoreIndex(i))
                .map(r -> BlockMatch.satisfies(cell.block(), r.before(), item.volatileProps()))
                .orElse(false);
    }

    /**
     * Whether the removal may touch the position at all: the registry must hold an entry of the item's source job for
     * this step's key (the current write's key, or an earlier one like a foundation over its own ground cut) and the
     * world must still show the block the entry names. Without that the block is someone's and stays: a rollback that
     * lost the position's record must never erase a block that was always there.
     */
    private static boolean owns(SiteView view, RestoreItem item, WorldCell cell) {
        return view.placedAt(item.pos())
                .filter(e -> e.jobId().equals(item.sourceJobId())
                        && (e.placementIndex() == item.sourceLedgerKey()
                        || e.earlierKeys().contains(item.sourceLedgerKey()))
                        && BlockMatch.satisfies(cell.block(), e.placed(), item.volatileProps()))
                .isPresent();
    }

    private static StepReport walk(ExecutionContext ctx, int cursor, int allowance, RunLog log) {
        List<IntPos> touched = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();
        JobProgram program = ctx.program();
        SiteView view = live(ctx);
        int c = cursor;
        int done = 0;
        while (c < program.size()) {
            // a piece (a door's two halves) is never split at the allowance: its partner would pop off with a drop
            if (done >= allowance && !continuesPiece(program, c)) {
                break;
            }
            boolean restore = program.isRestore(c);
            Step s = restore ? restore(ctx, c, touched, conflicts, log, view) : put(ctx, c, touched, conflicts, log, view);
            if (s.pause() != null) {
                return new StepReport(c, s.pause(), touched, s.shortage(), conflicts);
            }
            if (restore && !continuesPiece(program, c + 1)) {
                settlePieceEndingAt(ctx, c, log);
            }
            c++;
            done++;
        }
        return new StepReport(c, null, touched, List.of(), conflicts);
    }

    /** Neighbour updates for the positions of the piece this run really restored (a conflicted one stays untouched). */
    private static void settlePieceEndingAt(ExecutionContext ctx, int last, RunLog log) {
        List<IntPos> piece = new ArrayList<>();
        int i = last;
        piece.add(ctx.program().restore(i).pos());
        while (continuesPiece(ctx.program(), i)) {
            i--;
            piece.add(0, ctx.program().restore(i).pos());
        }
        piece.removeIf(p -> !log.restored.contains(p));
        if (!piece.isEmpty()) {
            ctx.world().settle(piece);
        }
    }

    /** Moves what one ledger operation still owes. A take pauses on a shortage and changes nothing. */
    private static Step settle(ExecutionContext ctx, RunLog log, OpKey key, List<ItemCount> owed) {
        if (owed.isEmpty()) {
            return Step.NEXT;
        }
        MaterialPort port = ctx.materials();
        List<Move> moves;
        if (key.op().takesFromOwner()) {
            List<Stock> stocks = port.stocks(Stocks.ids(owed));
            List<ItemCount> missing = Stocks.missing(owed, stocks);
            if (!missing.isEmpty()) {
                return new Step(PauseReason.MATERIALS_MISSING, missing);
            }
            moves = Stocks.takeMoves(owed, stocks);
        } else {
            Optional<String> target = port.giveTarget(owed);
            if (target.isEmpty()) {
                return new Step(PauseReason.NO_ROOM, owed);
            }
            moves = Stocks.giveMoves(owed, target.get());
        }
        if (!port.apply(moves)) {
            // the port refused moves it approved a moment ago (it changed under us): whatever this step wrote is
            // already journaled and registered, so recovery has what it needs — pause the run, do not throw mid-write
            return Step.pause(PauseReason.RECOVERY_NEEDED);
        }
        MaterialLedger ledger = ctx.ledgers().of(key.jobId());
        ledger.record(key.op(), key.ledgerKey(), owed);
        // the saved ledger then says which runs it already holds, so recovery never folds a run in twice
        ledger.markAppliedRun(log.run);
        log.moves.add(new WalEntry.OpMoves(key, moves));
        log.movedInventory |= moves.stream().anyMatch(m -> m.sourceId().equals(MaterialPort.INVENTORY));
        return Step.NEXT;
    }

    private static Step put(ExecutionContext ctx, int i, List<IntPos> touched, List<Conflict> conflicts, RunLog log,
                            SiteView view) {
        PutItem item = ctx.program().put(i);
        Placement p = item.placement();
        String jobId = ctx.job().jobId();
        int key = item.ledgerKey();
        OpKey charge = new OpKey(jobId, key, MaterialOp.CHARGE);
        OpKey yieldOp = new OpKey(jobId, key, MaterialOp.YIELD);
        return switch (planPut(view, ctx, item)) {
            case PutPlan.Unloaded u -> Step.pause(PauseReason.CHUNK_UNLOADED);
            case PutPlan.Conflicted f -> {
                ctx.outcome().skip(new SkippedPlacement(item.index(), p.pos(), SkippedPlacement.CONFLICT));
                yield Step.NEXT;
            }
            case PutPlan.Assembled a -> {
                // the assembly took this block into its contraption: air here is right, and the block is never placed again
                yield settle(ctx, log, charge, a.chargeOwed());
            }
            case PutPlan.Settle s -> {
                Step st = settle(ctx, log, charge, s.chargeOwed());
                yield st.pause() != null ? st : settle(ctx, log, yieldOp, s.yieldOwed());
            }
            case PutPlan.AlreadyThere a -> {
                ctx.outcome().skip(new SkippedPlacement(item.index(), p.pos(), SkippedPlacement.ALREADY_THERE));
                yield Step.NEXT;
            }
            case PutPlan.Refused r -> {
                if (ctx.outcome().addConflict(r.conflict())) {
                    conflicts.add(r.conflict());
                }
                if (!ctx.skipSiteChanges()) {
                    yield Step.pause(PauseReason.SITE_CHANGED);
                }
                ctx.outcome().skip(new SkippedPlacement(item.index(), p.pos(), SkippedPlacement.SITE_CHANGED));
                yield Step.NEXT;
            }
            case PutPlan.Place pl -> {
                if (!pl.chargeOwed().isEmpty()) {
                    List<ItemCount> missing = Stocks.missing(pl.chargeOwed(),
                            ctx.materials().stocks(Stocks.ids(pl.chargeOwed())));
                    if (!missing.isEmpty()) {
                        yield new Step(PauseReason.MATERIALS_MISSING, missing);
                    }
                }
                if (!pl.yieldOwed().isEmpty() && ctx.materials().giveTarget(pl.yieldOwed()).isEmpty()) {
                    // the cut ground has nowhere to go: wait for room before the world changes (nothing is dropped)
                    yield new Step(PauseReason.NO_ROOM, pl.yieldOwed());
                }
                PlaceResult result = ctx.world().place(p.pos(), p.block(), p.blockEntityConfig(), ctx.job().ownerUuid());
                if (result != PlaceResult.PLACED) {
                    ctx.outcome().skip(new SkippedPlacement(item.index(), p.pos(),
                            result == PlaceResult.DENIED ? SkippedPlacement.DENIED : SkippedPlacement.INVALID));
                    yield Step.NEXT;
                }
                // journal and register the write before the material settle: if the apply then fails, the placed
                // block is on the record (a rollback can remove it) and the run pauses instead of leaking the exception
                JournalRecord rec = putRecord(item, pl.cell(), pl.terrainCut());
                ctx.journal().record(rec);
                ctx.registry().apply(jobId, rec);
                log.written.add(rec.placementIndex());
                ctx.outcome().resolveConflictAt(p.pos());
                touched.add(p.pos());
                Step s = settle(ctx, log, charge, pl.chargeOwed());
                yield s.pause() != null || !pl.terrainCut() ? s : settle(ctx, log, yieldOp, pl.yieldOwed());
            }
        };
    }

    /**
     * Settles a removal for every ledger key of its source job at the position (earlier writes too: a foundation laid on
     * the job's own ground cut hands the cut ground back as well as the foundation's charge). {@code takeBackOverRefund}:
     * a refund whose charge never reached the disk is taken back (only on a re-walk of a done removal).
     */
    private static Step settleRemoval(ExecutionContext ctx, RunLog log, RestoreItem item, MaterialLedger source,
                                      boolean takeBackOverRefund) {
        for (int key : item.sourceKeys()) {
            Step s = settle(ctx, log, new OpKey(item.sourceJobId(), key, MaterialOp.RECLAIM), source.owedReclaim(key));
            if (s.pause() == null && takeBackOverRefund) {
                s = settle(ctx, log, new OpKey(item.sourceJobId(), key, MaterialOp.CHARGE),
                        ItemCount.minus(source.returned(key), source.consumed(key)));
            }
            if (s.pause() == null) {
                s = settle(ctx, log, new OpKey(item.sourceJobId(), key, MaterialOp.RETURN), source.owedReturn(key));
            }
            if (s.pause() != null) {
                return s;
            }
        }
        return Step.NEXT;
    }

    private static Step restore(ExecutionContext ctx, int i, List<IntPos> touched, List<Conflict> conflicts,
                                RunLog log, SiteView view) {
        RestoreItem item = ctx.program().restore(i);
        String jobId = ctx.job().jobId();
        MaterialLedger source = ctx.ledgers().of(item.sourceJobId());
        int index = JournalRecord.restoreIndex(i);
        return switch (planRestore(view, ctx, i, item)) {
            case RestorePlan.Unloaded u -> Step.pause(PauseReason.CHUNK_UNLOADED);
            case RestorePlan.AlreadyRestored a -> {
                if (a.journaled()) {
                    // this job restored it already (a crash cut the run short): settle what is still owed, and take back
                    // a refund whose charge never reached the disk (a refund is only for what was really paid)
                    yield settleRemoval(ctx, log, item, source, true);
                }
                // someone else emptied it: nothing is owed for a block this job never took away
                JournalRecord rec = restoreRecord(i, item, view.read(item.pos()));
                ctx.journal().record(rec);
                ctx.registry().apply(jobId, rec);
                yield Step.NEXT;
            }
            case RestorePlan.Conflicted f -> {
                if (ctx.outcome().addRestoreConflict(f.conflict())) {
                    conflicts.add(f.conflict());
                }
                ctx.outcome().skip(new SkippedPlacement(index, item.pos(), SkippedPlacement.CONFLICT));
                yield Step.NEXT;
            }
            case RestorePlan.Remove m -> {
                List<ItemCount> owedReclaim = List.of();
                List<ItemCount> owedReturn = List.of();
                for (int key : item.sourceKeys()) {
                    owedReclaim = ItemCount.plus(owedReclaim, source.owedReclaim(key));
                    owedReturn = ItemCount.plus(owedReturn, source.owedReturn(key));
                }
                if (!owedReclaim.isEmpty()) {
                    List<ItemCount> missing = Stocks.missing(owedReclaim,
                            ctx.materials().stocks(Stocks.ids(owedReclaim)));
                    if (!missing.isEmpty()) {
                        yield new Step(PauseReason.MATERIALS_MISSING, missing);
                    }
                }
                if (!owedReturn.isEmpty() && ctx.materials().giveTarget(owedReturn).isEmpty()) {
                    // the refund has nowhere to go: wait for room before the world changes (nothing is dropped)
                    yield new Step(PauseReason.NO_ROOM, owedReturn);
                }
                PlaceResult result = ctx.world().restore(item.pos(), item.restoreTo(), ctx.job().ownerUuid(),
                        item.dropContents());
                if (result != PlaceResult.PLACED) {
                    ctx.outcome().skip(new SkippedPlacement(index, item.pos(),
                            result == PlaceResult.DENIED ? SkippedPlacement.DENIED : SkippedPlacement.INVALID));
                    yield Step.NEXT;
                }
                // journal and register the removal before the material settle: if the apply then fails, what came
                // out is on the record and the run pauses for recovery instead of leaking the exception
                JournalRecord rec = restoreRecord(i, item, m.cell());
                ctx.journal().record(rec);
                ctx.registry().apply(jobId, rec);
                log.written.add(rec.placementIndex());
                log.restored.add(item.pos());
                touched.add(item.pos());
                yield settleRemoval(ctx, log, item, source, false);
            }
        };
    }
}
