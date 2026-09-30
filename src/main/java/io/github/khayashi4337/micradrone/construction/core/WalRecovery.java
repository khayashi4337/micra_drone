package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.BlockMatch;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongPredicate;

/**
 * Folds the runs a crash left after the last durable point into one job's saved journal, registry and ledgers (04 F-2,
 * F-7). The rule is to never guess: what the evidence proves is folded in, what it cannot prove is left to the owner.
 * <ul>
 *   <li>A run with its end: a written block counts only where the world holds exactly the planned block (anything else
 *   was lost with the unsaved world and is placed again by the re-walk from {@link Result#rewindTo()}); an inventory
 *   move counts only if the owner's saved data holds a transaction id at least the run's; a supply chest move cannot be
 *   proven (its chunk may or may not have been saved), so the run is ambiguous.</li>
 *   <li>A run without its end (the crash came inside it) is ambiguous.</li>
 * </ul>
 * An ambiguous job is left unchanged ({@link Result#ambiguous()}); the owner resolves it with adopt or discard, which
 * decides every ambiguous run of the job the same way. The residual limit: a player who places exactly the planned
 * block at a position of a finished run while the server is down is indistinguishable and is adopted.
 */
public final class WalRecovery {
    /** The owner's answer for runs the evidence cannot decide. */
    public enum Resolution { NONE, ADOPT, DISCARD }

    /** {@code explained}: the program positions the log's runs after the durable point account for (re-walked if lost). */
    public record Result(boolean ambiguous, List<String> reasons, int adopted, int lostMoves, int rewindTo,
                         Set<Integer> explained, List<IntPos> possiblyLostDrops) {
        public Result {
            reasons = List.copyOf(reasons);
            explained = Set.copyOf(explained);
            possiblyLostDrops = List.copyOf(possiblyLostDrops);
        }
    }

    /** Why a job waits for its owner: a run cut short inside, or a run that moved supply chest items. */
    public static final String REASON_CUT_SHORT = "cut-short";
    public static final String REASON_CHEST = "chest";

    private WalRecovery() {
    }

    public static Result recover(String jobId, List<WalEntry> log, JobProgram program, Journal journal, PlacedRegistry registry,
                                 LedgerBook ledgers, WorldPort world, long savedTx, Resolution resolution) {
        LongPredicate adopt = switch (resolution) {
            case NONE -> null;
            case ADOPT -> run -> true;
            case DISCARD -> run -> false;
        };
        return recover(jobId, log, program, journal, registry, ledgers, world, savedTx, adopt);
    }

    /**
     * {@code savedTx}: the transaction id the owner's saved data held at start-up (read before any new run).
     * {@code adoptRun}: the answer per ambiguous run, or null to report the ambiguity and change nothing.
     */
    static Result recover(String jobId, List<WalEntry> log, JobProgram program, Journal journal, PlacedRegistry registry,
                          LedgerBook ledgers, WorldPort world, long savedTx, LongPredicate adoptRun) {
        long durable = WriteAheadLog.durablePointIn(log);
        TreeMap<Long, List<WalEntry>> runs = new TreeMap<>();
        for (WalEntry e : log) {
            boolean mine = switch (e) {
                case WalEntry.RunStart s -> s.jobId().equals(jobId);
                case WalEntry.PlaceIntent p -> p.jobId().equals(jobId);
                case WalEntry.MaterialIntent m -> m.jobId().equals(jobId);
                case WalEntry.DropIntent d -> d.jobId().equals(jobId);
                case WalEntry.RunEnd r -> r.jobId().equals(jobId);
                case WalEntry.DurablePoint d -> false;
            };
            if (mine && WalEntry.runOf(e) > durable) {
                runs.computeIfAbsent(WalEntry.runOf(e), k -> new ArrayList<>()).add(e);
            }
        }
        List<String> reasons = new ArrayList<>();
        for (Map.Entry<Long, List<WalEntry>> r : runs.entrySet()) {
            WalEntry.RunEnd end = endOf(r.getValue());
            if (end == null && r.getValue().stream().anyMatch(e -> !(e instanceof WalEntry.RunStart))) {
                reasons.add(REASON_CUT_SHORT + ":" + r.getKey());
            } else if (end != null && movesChest(end)) {
                reasons.add(REASON_CHEST + ":" + r.getKey());
            }
        }
        if (!reasons.isEmpty() && adoptRun == null) {
            return new Result(true, reasons, 0, 0, program.size(), Set.of(), List.of());
        }
        Map<Integer, Integer> positionOfKey = new HashMap<>();
        for (int i = 0; i < program.size(); i++) {
            if (!program.isRestore(i)) {
                positionOfKey.put(program.put(i).ledgerKey(), i);
            }
        }
        int adopted = 0;
        // the writes of this job that happened (by the evidence or the owner's answer), in the order they were made; a
        // chunk is saved as one snapshot, so where the world shows a write, every earlier write of the job at that
        // position happened too (a ground cut under the foundation that now stands there)
        List<JournalRecord> writes = new ArrayList<>();
        for (Map.Entry<Long, List<WalEntry>> r : runs.entrySet()) {
            WalEntry.RunEnd end = endOf(r.getValue());
            boolean take = (end != null && !movesChest(end)) || adoptRun.test(r.getKey());
            for (WalEntry e : r.getValue()) {
                if (e instanceof WalEntry.PlaceIntent pi && take
                        && (end == null || end.written().contains(pi.record().placementIndex()))) {
                    writes.add(pi.record());
                }
            }
        }
        Map<IntPos, Integer> lastShown = new HashMap<>();
        for (int i = 0; i < writes.size(); i++) {
            WorldCell cell = world.read(writes.get(i).pos());
            if (cell.loaded() && BlockMatch.exact(cell.block(), writes.get(i).placed(), Set.of())) {
                lastShown.put(writes.get(i).pos(), i);
            }
        }
        for (int i = 0; i < writes.size(); i++) {
            JournalRecord rec = writes.get(i);
            if (i <= lastShown.getOrDefault(rec.pos(), -1)) {
                if (journal.record(rec)) {
                    adopted++;
                }
                registry.apply(jobId, rec);
            }
        }
        rebuildEntries(jobId, writes, journal, registry, world);
        int lost = 0;
        int rewindTo = program.size();
        java.util.Set<Integer> explained = new java.util.HashSet<>();
        List<IntPos> drops = new ArrayList<>();
        for (Map.Entry<Long, List<WalEntry>> r : runs.entrySet()) {
            long run = r.getKey();
            WalEntry.RunEnd end = endOf(r.getValue());
            boolean decided = end != null && !movesChest(end);
            boolean take = decided || adoptRun.test(run);
            // whether each ledger already holds this run is read once, before any of the run's operations is folded in
            Map<String, Boolean> held = new HashMap<>();
            java.util.function.Function<String, Boolean> holds = job -> held.computeIfAbsent(job,
                    k -> run <= ledgers.of(k).appliedRun());
            for (WalEntry e : r.getValue()) {
                if (e instanceof WalEntry.PlaceIntent pi) {
                    JournalRecord rec = pi.record();
                    int at = rec.placementIndex() < 0 ? -1 - rec.placementIndex()
                            : positionOfKey.getOrDefault(rec.placementIndex(), program.size());
                    rewindTo = Math.min(rewindTo, at);
                    explained.add(at);
                } else if (e instanceof WalEntry.DropIntent d) {
                    drops.add(d.pos());
                } else if (e instanceof WalEntry.MaterialIntent mi && end == null) {
                    // a run cut short: its moves are unknown; the owner's answer decides whether the operation happened
                    MaterialLedger ledger = ledgers.of(mi.key().jobId());
                    boolean already = holds.apply(mi.key().jobId());
                    if (take && !already) {
                        ledger.replay(mi.key().op(), mi.key().ledgerKey(), mi.items());
                    } else if (!take && already) {
                        ledger.unrecord(mi.key().op(), mi.key().ledgerKey(), mi.items());
                    }
                }
            }
            if (end == null) {
                continue;
            }
            for (WalEntry.OpMoves om : end.moves()) {
                MaterialLedger ledger = ledgers.of(om.key().jobId());
                List<Move> kept = new ArrayList<>();
                List<Move> gone = new ArrayList<>();
                for (Move m : om.moves()) {
                    boolean durableMove = m.sourceId().equals(MaterialPort.INVENTORY) ? savedTx >= run
                            : m.sourceId().startsWith(MaterialPort.CHEST_PREFIX) ? take : true;
                    (durableMove ? kept : gone).add(m);
                }
                lost += gone.size();
                if (!holds.apply(om.key().jobId())) {
                    if (!kept.isEmpty()) {
                        ledger.replay(om.key().op(), om.key().ledgerKey(), Stocks.items(kept));
                    }
                } else if (!gone.isEmpty()) {
                    ledger.unrecord(om.key().op(), om.key().ledgerKey(), Stocks.items(gone));
                }
            }
            held.keySet().forEach(job -> ledgers.of(job).markAppliedRun(run));
        }
        return new Result(false, reasons, adopted, lost, rewindTo, explained, drops);
    }

    /**
     * The registry entry of each position this job wrote after the durable point, made to agree with the world: the
     * saved files may be newer than the saved world (an autosave writes both, but a chunk can miss it), so an entry can
     * name a write the world lost. The entry becomes this job's last write the world shows, with the earlier ones as
     * earlier keys; where the world shows none of them but the pre-build block, the entry goes.
     */
    private static void rebuildEntries(String jobId, List<JournalRecord> writes, Journal journal, PlacedRegistry registry,
                                       WorldPort world) {
        Map<IntPos, List<JournalRecord>> byPos = new HashMap<>();
        for (JournalRecord r : journal.records()) {
            byPos.computeIfAbsent(r.pos(), k -> new ArrayList<>()).add(r);
        }
        java.util.Set<IntPos> touched = new java.util.LinkedHashSet<>();
        writes.forEach(w -> touched.add(w.pos()));
        for (IntPos pos : touched) {
            java.util.Optional<PlacedEntry> entry = registry.at(pos);
            if (entry.isEmpty() || !entry.get().jobId().equals(jobId)) {
                continue;
            }
            // a removal (negative index) comes before the job's placements at the same position
            List<JournalRecord> chain = new ArrayList<>(byPos.getOrDefault(pos, List.of()));
            chain.removeIf(r -> r.ledgerKey() == JournalRecord.NO_LEDGER_KEY);
            chain.sort(java.util.Comparator.comparingInt(JournalRecord::ledgerKey));
            WorldCell cell = world.read(pos);
            int last = -1;
            for (int i = 0; i < chain.size(); i++) {
                if (cell.loaded() && BlockMatch.exact(cell.block(), chain.get(i).placed(), Set.of())) {
                    last = i;
                }
            }
            BlockSpec before = entry.get().before();
            Map<IntPos, PlacedEntry> one = new HashMap<>();
            if (last >= 0) {
                List<Integer> earlier = new ArrayList<>();
                for (int i = 0; i < last; i++) {
                    earlier.add(chain.get(i).ledgerKey());
                }
                one.put(pos, new PlacedEntry(chain.get(last).placed(), before, jobId, chain.get(last).ledgerKey(), earlier));
                registry.putAll(one);
            } else if (cell.loaded() && BlockMatch.exact(cell.block(), before, Set.of())) {
                registry.remove(pos);
            }
        }
    }

    private static WalEntry.RunEnd endOf(List<WalEntry> entries) {
        for (WalEntry e : entries) {
            if (e instanceof WalEntry.RunEnd end) {
                return end;
            }
        }
        return null;
    }

    private static boolean movesChest(WalEntry.RunEnd end) {
        return end.moves().stream().flatMap(om -> om.moves().stream())
                .anyMatch(m -> m.sourceId().startsWith(MaterialPort.CHEST_PREFIX));
    }

    /** The job's runs after the last durable point, the part of the log an ambiguous job keeps for its owner's answer. */
    public static List<WalEntry> pendingPart(String jobId, List<WalEntry> log) {
        long durable = WriteAheadLog.durablePointIn(log);
        List<WalEntry> out = new ArrayList<>();
        for (WalEntry e : log) {
            if (!(e instanceof WalEntry.DurablePoint) && WalEntry.runOf(e) > durable && jobOf(e).equals(jobId)) {
                out.add(e);
            }
        }
        return out;
    }

    private static String jobOf(WalEntry e) {
        return switch (e) {
            case WalEntry.RunStart s -> s.jobId();
            case WalEntry.PlaceIntent p -> p.jobId();
            case WalEntry.MaterialIntent m -> m.jobId();
            case WalEntry.DropIntent d -> d.jobId();
            case WalEntry.RunEnd r -> r.jobId();
            case WalEntry.DurablePoint d -> "";
        };
    }
}
