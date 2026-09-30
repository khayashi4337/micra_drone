package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The blocks this project placed in one claim (D-25): only these may be removed or changed later. Each entry keeps the
 * block from before the project first touched the position, so a rollback restores the original ground; the placer's
 * job and ledger key tell which ledger answers for the current block's material. Kept until the claim is released (D-23).
 */
public final class PlacedRegistry {
    public static final int SCHEMA_VERSION = 1;
    /** The public view's fixed order (x, then y, then z): a hash map's order must not leak into results. */
    private static final Comparator<IntPos> POSITION_ORDER = Comparator.comparingInt(IntPos::x)
            .thenComparingInt(IntPos::y).thenComparingInt(IntPos::z);

    private final String claimId;
    private final Map<IntPos, PlacedEntry> placed = new TreeMap<>(POSITION_ORDER);
    private final Map<String, AssemblyResult> assemblies = new TreeMap<>();

    public PlacedRegistry(String claimId) {
        this.claimId = Objects.requireNonNull(claimId, "claimId");
    }

    public void apply(String jobId, JournalRecord r) {
        PlacedEntry e = placed.get(r.pos());
        if (e == null) {
            // a removal never creates an entry: re-running a removal after a crash must not claim the ground as ours
            if (r.ledgerKey() != JournalRecord.NO_LEDGER_KEY && !r.placed().equals(r.before())) {
                placed.put(r.pos(), new PlacedEntry(r.placed(), r.before(), jobId, r.ledgerKey()));
            }
            return;
        }
        // a replay of the current owner's write does nothing (crash recovery applies a finished run again); a
        // different record under the same ledger key is a bug: one key settles one write (04 F-7)
        if (r.ledgerKey() == e.placementIndex()) {
            if (r.placed().equals(e.placed()) && jobId.equals(e.jobId())) {
                return;
            }
            throw new IllegalArgumentException("ledger key " + r.ledgerKey() + " at " + r.pos() + " already wrote "
                    + e.placed() + " for " + e.jobId() + ", not " + r.placed() + " for " + jobId);
        }
        // a write the position already superseded is an old replay: applying it again must not rewind the owner
        // (its key is settled as one of the earlier keys when the position is removed)
        if (e.earlierKeys().contains(r.ledgerKey())) {
            return;
        }
        if (r.placed().equals(e.before())) {
            placed.remove(r.pos());
            return;
        }
        // a removal that leaves a block other than the pre-build one is not a restore
        if (r.ledgerKey() == JournalRecord.NO_LEDGER_KEY) {
            throw new IllegalStateException("a removal at " + r.pos() + " must restore " + e.before() + ", not "
                    + r.placed());
        }
        // a first-round write over a held position continues the position's story: its record must name the block the
        // entry holds. Repair-round writes (own ledger keys, JobProgram.repair) legitimately re-place a block the
        // world changed or lost since it was written, so their 'before' may differ.
        if (r.ledgerKey() < JobProgram.LEDGER_ROUND_STRIDE && !r.before().equals(e.placed())) {
            throw new IllegalStateException("a write at " + r.pos() + " expects " + r.before()
                    + " but the registry holds " + e.placed());
        }
        // the same job writing the position again (a foundation on its own ground cut) keeps the earlier key: a removal
        // settles every key of it (the cut ground is taken back as well as the foundation's charge returned)
        List<Integer> earlier = new ArrayList<>();
        if (e.jobId().equals(jobId)) {
            earlier.addAll(e.earlierKeys());
            if (!earlier.contains(e.placementIndex())) {
                earlier.add(e.placementIndex());
            }
        }
        placed.put(r.pos(), new PlacedEntry(r.placed(), e.before(), jobId, r.ledgerKey(), earlier));
    }

    /** Crash recovery only: the entry of a position whose every write by the project the world lost. */
    public void remove(IntPos pos) {
        placed.remove(pos);
    }

    public void putAll(Map<IntPos, PlacedEntry> entries) {
        placed.putAll(entries);
    }

    /** The assembled individual of a group (P10/P13 write it; P4 stores and restores it). */
    public void putAssembly(AssemblyResult result) {
        assemblies.put(result.groupId(), result);
    }

    public Optional<PlacedEntry> at(IntPos pos) {
        return Optional.ofNullable(placed.get(pos));
    }

    public boolean contains(IntPos pos) {
        return placed.containsKey(pos);
    }

    public Map<IntPos, PlacedEntry> placed() {
        return Collections.unmodifiableMap(placed);
    }

    public Map<String, AssemblyResult> assemblies() {
        return Collections.unmodifiableMap(assemblies);
    }

    public String claimId() {
        return claimId;
    }

    public int size() {
        return placed.size();
    }
}
