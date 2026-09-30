package io.github.khayashi4337.micradrone.construction.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
        if (byIndex.containsKey(r.placementIndex())) {
            return false;
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
        List<UndoEntry> out = new ArrayList<>();
        for (JournalRecord r : byIndex.values()) {
            out.add(r.undo());
        }
        out.sort(TOP_DOWN);
        return out;
    }
}
