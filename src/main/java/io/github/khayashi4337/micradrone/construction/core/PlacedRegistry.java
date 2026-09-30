package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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

    private final String claimId;
    private final Map<IntPos, PlacedEntry> placed = new HashMap<>();
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
        if (r.placed().equals(e.before())) {
            placed.remove(r.pos());
            return;
        }
        // the same job writing the position again (a foundation on its own ground cut) keeps the earlier key: a removal
        // settles every key of it (the cut ground is taken back as well as the foundation's charge returned)
        List<Integer> earlier = new ArrayList<>();
        if (e.jobId().equals(jobId) && e.placementIndex() != r.ledgerKey()) {
            earlier.addAll(e.earlierKeys());
            earlier.add(e.placementIndex());
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
