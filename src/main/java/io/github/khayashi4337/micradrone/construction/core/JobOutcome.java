package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.Conflict;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** What a job could not do as planned: conflicts it left untouched and placements it skipped, reported to the owner. */
public final class JobOutcome {
    private final Map<IntPos, Conflict> conflicts = new LinkedHashMap<>();
    /** Positions a removal left alone: a later placement at the same position must not overwrite them (MODIFY). */
    private final Set<IntPos> restoreConflicts = new HashSet<>();
    private final TreeMap<Integer, SkippedPlacement> skipped = new TreeMap<>();

    /** True the first time a position is reported. */
    public boolean addConflict(Conflict c) {
        return conflicts.putIfAbsent(c.pos(), c) == null;
    }

    /** A conflict found while removing: reported like any other, and it also blocks a placement at the same position. */
    public boolean addRestoreConflict(Conflict c) {
        restoreConflicts.add(c.pos());
        return addConflict(c);
    }

    public boolean hasRestoreConflictAt(IntPos pos) {
        return restoreConflicts.contains(pos);
    }

    /** A placement succeeded where a site-change conflict was recorded (the obstacle went away): no longer a conflict. */
    public void resolveConflictAt(IntPos pos) {
        if (!restoreConflicts.contains(pos)) {
            conflicts.remove(pos);
        }
    }

    public void skip(SkippedPlacement s) {
        skipped.put(s.index(), s);
    }

    public List<Conflict> conflicts() {
        return List.copyOf(conflicts.values());
    }

    public List<SkippedPlacement> skipped() {
        return List.copyOf(skipped.values());
    }

    public boolean isSkipped(int index) {
        return skipped.containsKey(index);
    }

    public Set<Integer> deniedIndexes() {
        Set<Integer> out = new HashSet<>();
        for (SkippedPlacement s : skipped.values()) {
            if (SkippedPlacement.DENIED.equals(s.reason())) {
                out.add(s.index());
            }
        }
        return Collections.unmodifiableSet(out);
    }
}
