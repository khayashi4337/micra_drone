package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * What a job changed, position by position (04 F-2: up to 20,000 undo entries). The first record of a placement index
 * wins: a repeat (resume, repair) never overwrites the pre-build block, so a rollback always restores the original.
 */
public final class Journal {
    public static final int MAX_ENTRIES = SafetyLimits.DEFAULT_MAX_PLACEMENTS;
    private static final Comparator<UndoEntry> TOP_DOWN = Comparator.<UndoEntry>comparingInt(u -> -u.pos().y())
            .thenComparingInt(u -> u.pos().z()).thenComparingInt(u -> u.pos().x());

    private final int maxEntries;
    private final TreeMap<Integer, JournalRecord> byIndex = new TreeMap<>();

    public Journal() {
        this(MAX_ENTRIES);
    }

    public Journal(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    public boolean record(JournalRecord r) {
        JournalRecord held = byIndex.get(r.placementIndex());
        if (held != null) {
            // a rerun logs the same record again (the write is not done twice); a different record under one index
            // would overwrite this position's pre-build state, so it is a bug, not a rerun ('before' may differ: it
            // is what the run saw, the first record's is the one the undo uses)
            if (held.pos().equals(r.pos()) && held.placed().equals(r.placed()) && held.ledgerKey() == r.ledgerKey()
                    && held.terrainCut() == r.terrainCut()) {
                return false;
            }
            throw new IllegalStateException("journal index " + r.placementIndex() + " already holds a different record");
        }
        if (byIndex.size() >= maxEntries) {
            throw new IllegalStateException("the journal is full (" + maxEntries + " entries)");
        }
        byIndex.put(r.placementIndex(), r);
        return true;
    }

    public Optional<JournalRecord> at(int index) {
        return Optional.ofNullable(byIndex.get(index));
    }

    public int size() {
        return byIndex.size();
    }

    public List<JournalRecord> records() {
        return List.copyOf(byIndex.values());
    }

    public List<UndoEntry> undo() {
        // one entry per position, the first write's 'before': applying them top-down restores the pre-build block
        // even where the job wrote the position twice (a foundation on its own ground cut)
        Map<IntPos, UndoEntry> first = new LinkedHashMap<>();
        for (JournalRecord r : byIndex.values()) {
            first.putIfAbsent(r.pos(), r.undo());
        }
        List<UndoEntry> out = new ArrayList<>(first.values());
        out.sort(TOP_DOWN);
        return out;
    }
}
