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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Runs up to {@code allowance} steps of a job program from {@code cursor} (04 F-2, F-5, F-7). Every step reads the
 * world first and decides with the same rules as the approval did, so a re-run from an older cursor is harmless:
 * journaled positions that already hold their block are skipped, and the ledger is settled to what is still owed, never
 * more. One call is one run of the write-ahead log: intents are durable before the first change, the run's end right
 * after the last one, then the owner's inventory is saved with the run as its transaction id. Nothing is ever dropped
 * inside a material operation: a give that does not fit pauses (NO_ROOM) before the world is touched.
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

        boolean isEmpty() {
            return written.isEmpty() && moves.isEmpty();
        }
    }

    private ConstructionExecutor() {
    }

    public static StepReport run(ExecutionContext ctx, int cursor, int allowance) {
        WriteAheadLog wal = ctx.wal();
        String jobId = ctx.job().jobId();
        long run = wal.newRun();
        List<WalEntry> intents = intents(ctx, cursor, allowance, run);
        if (!intents.isEmpty()) {
            wal.append(new WalEntry.RunStart(jobId, run));
            intents.forEach(wal::append);
            wal.flush();
            wal.at(WriteAheadLog.Barrier.AFTER_INTENTS);
        }
        RunLog log = new RunLog(run);
        StepReport report = walk(ctx, cursor, allowance, log);
        if (!log.isEmpty()) {
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

    /**
     * The registry entry a put may treat as its own block: only a repair round's step (ledger key of round 1 or later,
     * JobProgram.repair) re-places the project's block of the same id in a wrong state. A first-round step (BUILD,
     * MODIFY) never does: a MODIFY's changed position was checked against what the project placed (its removal, with
     * BlockMatch.satisfies and the volatile states) and a mismatch is a lasting Conflict, not something to overwrite.
     */
    static Optional<BlockSpec> ownedFor(ExecutionContext ctx, PutItem item) {
        return item.ledgerKey() >= JobProgram.LEDGER_ROUND_STRIDE ? owned(ctx, item.placement().pos()) : Optional.empty();
    }

    /**
     * A step this job did and then built over (a ground cut under the foundation): the registry's entry is this job's
     * later write and lists this step's key as an earlier one. A re-walk from an older cursor counts it as done.
     */
    static boolean superseded(ExecutionContext ctx, PutItem item, Optional<JournalRecord> journaled) {
        IntPos pos = item.placement().pos();
        return journaled.isPresent() && ctx.registry().at(pos)
                .filter(e -> e.jobId().equals(ctx.job().jobId()) && e.earlierKeys().contains(item.ledgerKey())
                        && BlockMatch.exact(ctx.world().read(pos).block(), e.placed(), Set.of())).isPresent();
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

    /** The read-only first pass: what this run may write and move, logged before anything changes. */
    static List<WalEntry> intents(ExecutionContext ctx, int cursor, int allowance, long run) {
        List<WalEntry> out = new ArrayList<>();
        JobProgram program = ctx.program();
        String jobId = ctx.job().jobId();
        MaterialLedger ledger = ctx.ledgers().of(jobId);
        int done = 0;
        for (int c = cursor; c < program.size(); c++, done++) {
            if (done >= allowance && !continuesPiece(program, c)) {
                break;
            }
            if (program.isRestore(c)) {
                RestoreItem item = program.restore(c);
                WorldCell cell = ctx.world().read(item.pos());
                if (!cell.loaded()) {
                    break;
                }
                MaterialLedger source = ctx.ledgers().of(item.sourceJobId());
                if (BlockMatch.satisfies(cell.block(), item.restoreTo(), Set.of())) {
                    if (ctx.journal().at(JournalRecord.restoreIndex(c)).isEmpty()) {
                        continue;
                    }
                } else if (Conflicts.detect(item.pos(), item.expectedNow(), cell.observed(), item.volatileProps()).isPresent()) {
                    continue;
                } else {
                    out.add(new WalEntry.PlaceIntent(jobId, run, restoreRecord(c, item, cell)));
                    if (item.dropContents() && cell.observed().hasBlockEntity()) {
                        out.add(new WalEntry.DropIntent(jobId, run, item.pos()));
                    }
                }
                for (int key : item.sourceKeys()) {
                    intend(out, jobId, run, new OpKey(item.sourceJobId(), key, MaterialOp.RECLAIM), source.owedReclaim(key));
                    intend(out, jobId, run, new OpKey(item.sourceJobId(), key, MaterialOp.CHARGE),
                            ItemCount.minus(source.returned(key), source.consumed(key)));
                    intend(out, jobId, run, new OpKey(item.sourceJobId(), key, MaterialOp.RETURN), source.owedReturn(key));
                }
                continue;
            }
            PutItem item = program.put(c);
            Placement p = item.placement();
            WorldCell cell = ctx.world().read(p.pos());
            if (!cell.loaded()) {
                break;
            }
            if (ctx.outcome().hasRestoreConflictAt(p.pos())) {
                continue;
            }
            if (assembledAway(ctx, p)) {
                if (survival(ctx)) {
                    intend(out, jobId, run, new OpKey(jobId, item.ledgerKey(), MaterialOp.CHARGE),
                            ItemCount.minus(cost(p), ledger.consumed(item.ledgerKey())));
                }
                continue;
            }
            Optional<JournalRecord> journaled = ctx.journal().at(item.ledgerKey());
            ReplaceDecision d = superseded(ctx, item, journaled) ? new ReplaceDecision.AlreadyDone()
                    : ReplaceRules.decide(p, cell, journaled.isPresent(), ownedFor(ctx, item));
            if (d instanceof ReplaceDecision.Refused) {
                if (!ctx.skipSiteChanges()) {
                    break;
                }
                continue;
            }
            int key = item.ledgerKey();
            if (journaled.isEmpty() && ownedFor(ctx, item).isPresent() && BlockMatch.satisfies(cell.block(), p.block(), Set.of())) {
                continue;
            }
            if (d instanceof ReplaceDecision.Place place) {
                boolean cut = survival(ctx) && place.destruction() == Destruction.TERRAIN;
                out.add(new WalEntry.PlaceIntent(jobId, run, putRecord(item, cell, cut)));
                if (survival(ctx)) {
                    intend(out, jobId, run, new OpKey(jobId, key, MaterialOp.CHARGE),
                            ItemCount.minus(cost(p), ledger.consumed(key)));
                    if (cut) {
                        intend(out, jobId, run, new OpKey(jobId, key, MaterialOp.YIELD),
                                ItemCount.minus(ground(cell.block()), ledger.yielded(key)));
                    }
                }
            } else if (survival(ctx)) {
                intend(out, jobId, run, new OpKey(jobId, key, MaterialOp.CHARGE), ItemCount.minus(cost(p), ledger.consumed(key)));
                JournalRecord rec = journaled.orElseThrow();
                if (rec.terrainCut()) {
                    intend(out, jobId, run, new OpKey(jobId, key, MaterialOp.YIELD),
                            ItemCount.minus(ground(rec.before()), ledger.yielded(key)));
                }
            }
        }
        return out;
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

    private static StepReport walk(ExecutionContext ctx, int cursor, int allowance, RunLog log) {
        List<IntPos> touched = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();
        JobProgram program = ctx.program();
        int c = cursor;
        int done = 0;
        while (c < program.size()) {
            // a piece (a door's two halves) is never split at the allowance: its partner would pop off with a drop
            if (done >= allowance && !continuesPiece(program, c)) {
                break;
            }
            boolean restore = program.isRestore(c);
            Step s = restore ? restore(ctx, c, touched, conflicts, log) : put(ctx, c, touched, conflicts, log);
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
            throw new IllegalStateException("materials changed on the main thread between the count and the move");
        }
        MaterialLedger ledger = ctx.ledgers().of(key.jobId());
        ledger.record(key.op(), key.ledgerKey(), owed);
        // the saved ledger then says which runs it already holds, so recovery never folds a run in twice
        ledger.markAppliedRun(log.run);
        log.moves.add(new WalEntry.OpMoves(key, moves));
        log.movedInventory |= moves.stream().anyMatch(m -> m.sourceId().equals(MaterialPort.INVENTORY));
        return Step.NEXT;
    }

    private static Step put(ExecutionContext ctx, int i, List<IntPos> touched, List<Conflict> conflicts, RunLog log) {
        PutItem item = ctx.program().put(i);
        Placement p = item.placement();
        WorldCell cell = ctx.world().read(p.pos());
        if (!cell.loaded()) {
            return Step.pause(PauseReason.CHUNK_UNLOADED);
        }
        if (ctx.outcome().hasRestoreConflictAt(p.pos())) {
            ctx.outcome().skip(new SkippedPlacement(item.index(), p.pos(), SkippedPlacement.CONFLICT));
            return Step.NEXT;
        }
        String jobId = ctx.job().jobId();
        MaterialLedger ledger = ctx.ledgers().of(jobId);
        int key = item.ledgerKey();
        OpKey charge = new OpKey(jobId, key, MaterialOp.CHARGE);
        OpKey yield = new OpKey(jobId, key, MaterialOp.YIELD);
        if (assembledAway(ctx, p)) {
            // the assembly took this block into its contraption: air here is right, and the block is never placed again
            return survival(ctx) ? settle(ctx, log, charge, ItemCount.minus(cost(p), ledger.consumed(key))) : Step.NEXT;
        }
        Optional<JournalRecord> journaled = ctx.journal().at(item.ledgerKey());
        ReplaceDecision d = superseded(ctx, item, journaled) ? new ReplaceDecision.AlreadyDone()
                : ReplaceRules.decide(p, cell, journaled.isPresent(), ownedFor(ctx, item));
        if (d instanceof ReplaceDecision.AlreadyDone) {
            if (!survival(ctx)) {
                return Step.NEXT;
            }
            // the block is there; settle what the ledger still owes (a crash can leave the charge or the ground unsettled)
            Step s = settle(ctx, log, charge, ItemCount.minus(cost(p), ledger.consumed(key)));
            if (s.pause() != null) {
                return s;
            }
            JournalRecord rec = journaled.orElseThrow();
            return rec.terrainCut() ? settle(ctx, log, yield, ItemCount.minus(ground(rec.before()), ledger.yielded(key)))
                    : Step.NEXT;
        }
        if (journaled.isEmpty() && ownedFor(ctx, item).isPresent() && BlockMatch.satisfies(cell.block(), p.block(), Set.of())) {
            // a repair step (its own ledger key) that finds our block already right: nothing to place, nothing to charge
            ctx.outcome().skip(new SkippedPlacement(item.index(), p.pos(), SkippedPlacement.ALREADY_THERE));
            return Step.NEXT;
        }
        if (d instanceof ReplaceDecision.Refused) {
            Conflict conflict = new Conflict(p.pos(), p.block(), cell.observed(),
                    cell.block().isAir() ? ConflictKind.MISSING : ConflictKind.PLAYER_MODIFIED);
            if (ctx.outcome().addConflict(conflict)) {
                conflicts.add(conflict);
            }
            if (!ctx.skipSiteChanges()) {
                return Step.pause(PauseReason.SITE_CHANGED);
            }
            ctx.outcome().skip(new SkippedPlacement(item.index(), p.pos(), SkippedPlacement.SITE_CHANGED));
            return Step.NEXT;
        }
        Destruction destruction = ((ReplaceDecision.Place) d).destruction();
        List<ItemCount> owed = survival(ctx) ? ItemCount.minus(cost(p), ledger.consumed(key)) : List.of();
        if (!owed.isEmpty()) {
            List<ItemCount> missing = Stocks.missing(owed, ctx.materials().stocks(Stocks.ids(owed)));
            if (!missing.isEmpty()) {
                return new Step(PauseReason.MATERIALS_MISSING, missing);
            }
        }
        boolean cutting = survival(ctx) && destruction == Destruction.TERRAIN;
        List<ItemCount> handOver = cutting ? ItemCount.minus(ground(cell.block()), ledger.yielded(key)) : List.of();
        if (!handOver.isEmpty() && ctx.materials().giveTarget(handOver).isEmpty()) {
            // the cut ground has nowhere to go: wait for room before the world changes (nothing is dropped)
            return new Step(PauseReason.NO_ROOM, handOver);
        }
        PlaceResult result = ctx.world().place(p.pos(), p.block(), p.blockEntityConfig(), ctx.job().ownerUuid());
        if (result != PlaceResult.PLACED) {
            ctx.outcome().skip(new SkippedPlacement(item.index(), p.pos(),
                    result == PlaceResult.DENIED ? SkippedPlacement.DENIED : SkippedPlacement.INVALID));
            return Step.NEXT;
        }
        settle(ctx, log, charge, owed);
        boolean cut = survival(ctx) && destruction == Destruction.TERRAIN;
        JournalRecord rec = putRecord(item, cell, cut);
        ctx.journal().record(rec);
        ctx.registry().apply(jobId, rec);
        log.written.add(rec.placementIndex());
        if (cut) {
            settle(ctx, log, yield, ItemCount.minus(ground(cell.block()), ledger.yielded(key)));
        }
        ctx.outcome().resolveConflictAt(p.pos());
        touched.add(p.pos());
        return Step.NEXT;
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

    private static Step restore(ExecutionContext ctx, int i, List<IntPos> touched, List<Conflict> conflicts, RunLog log) {
        RestoreItem item = ctx.program().restore(i);
        WorldCell cell = ctx.world().read(item.pos());
        if (!cell.loaded()) {
            return Step.pause(PauseReason.CHUNK_UNLOADED);
        }
        String jobId = ctx.job().jobId();
        MaterialLedger source = ctx.ledgers().of(item.sourceJobId());
        JournalRecord rec = restoreRecord(i, item, cell);
        if (BlockMatch.satisfies(cell.block(), item.restoreTo(), Set.of())) {
            if (ctx.journal().at(rec.placementIndex()).isEmpty()) {
                // someone else emptied it: nothing is owed for a block this job never took away
                ctx.journal().record(rec);
                ctx.registry().apply(jobId, rec);
                return Step.NEXT;
            }
            // this job restored it already (a crash cut the run short): settle what is still owed, and take back a refund
            // whose charge never reached the disk (a refund can only be for what was really paid)
            return settleRemoval(ctx, log, item, source, true);
        }
        Optional<Conflict> conflict = Conflicts.detect(item.pos(), item.expectedNow(), cell.observed(), item.volatileProps());
        if (conflict.isPresent()) {
            if (ctx.outcome().addRestoreConflict(conflict.get())) {
                conflicts.add(conflict.get());
            }
            ctx.outcome().skip(new SkippedPlacement(rec.placementIndex(), item.pos(), SkippedPlacement.CONFLICT));
            return Step.NEXT;
        }
        List<ItemCount> owedReclaim = List.of();
        List<ItemCount> owedReturn = List.of();
        for (int key : item.sourceKeys()) {
            owedReclaim = ItemCount.plus(owedReclaim, source.owedReclaim(key));
            owedReturn = ItemCount.plus(owedReturn, source.owedReturn(key));
        }
        if (!owedReclaim.isEmpty()) {
            List<ItemCount> missing = Stocks.missing(owedReclaim, ctx.materials().stocks(Stocks.ids(owedReclaim)));
            if (!missing.isEmpty()) {
                return new Step(PauseReason.MATERIALS_MISSING, missing);
            }
        }
        if (!owedReturn.isEmpty() && ctx.materials().giveTarget(owedReturn).isEmpty()) {
            // the refund has nowhere to go: wait for room before the world changes (nothing is dropped)
            return new Step(PauseReason.NO_ROOM, owedReturn);
        }
        PlaceResult result = ctx.world().restore(item.pos(), item.restoreTo(), ctx.job().ownerUuid(), item.dropContents());
        if (result != PlaceResult.PLACED) {
            ctx.outcome().skip(new SkippedPlacement(rec.placementIndex(), item.pos(),
                    result == PlaceResult.DENIED ? SkippedPlacement.DENIED : SkippedPlacement.INVALID));
            return Step.NEXT;
        }
        settleRemoval(ctx, log, item, source, false);
        ctx.journal().record(rec);
        ctx.registry().apply(jobId, rec);
        log.written.add(rec.placementIndex());
        log.restored.add(item.pos());
        touched.add(item.pos());
        return Step.NEXT;
    }
}
