package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.Conflict;
import io.github.khayashi4337.micradrone.build.compile.ConflictKind;
import io.github.khayashi4337.micradrone.build.compile.ObservedBlock;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A job's outcome as a tree: {@code {"conflicts": [[x,y,z,"expected","observed",hasBE,"beType","KIND"]],
 * "restoreConflicts": [[x,y,z]], "skipped": [[index,x,y,z,"reason"]]}}. The restore conflicts are saved as positions
 * (their full conflict records sit in {@code conflicts}): without them a {@code MODIFY} after a restart would place
 * over a position a removal had refused to touch (04 F-2).
 */
public final class OutcomeCodec {
    private static final String KEY_CONFLICTS = "conflicts";
    private static final String KEY_RESTORE_CONFLICTS = "restoreConflicts";
    private static final String KEY_SKIPPED = "skipped";
    private static final int CONFLICT_FIELDS = 8;
    private static final int SKIPPED_FIELDS = 5;
    private static final int POS_FIELDS = 3;

    private OutcomeCodec() {
    }

    public static Object toTree(JobOutcome o) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> conflicts = new ArrayList<>();
        List<Object> restoreConflicts = new ArrayList<>();
        for (Conflict c : o.conflicts()) {
            conflicts.add(List.of((long) c.pos().x(), (long) c.pos().y(), (long) c.pos().z(),
                    c.expected().toString(), c.observed().block().toString(), c.observed().hasBlockEntity(),
                    c.observed().blockEntityType(), c.kind().name()));
            if (o.hasRestoreConflictAt(c.pos())) {
                restoreConflicts.add(posTree(c.pos()));
            }
        }
        m.put(KEY_CONFLICTS, conflicts);
        m.put(KEY_RESTORE_CONFLICTS, restoreConflicts);
        List<Object> skipped = new ArrayList<>();
        for (SkippedPlacement s : o.skipped()) {
            skipped.add(List.of((long) s.index(), (long) s.pos().x(), (long) s.pos().y(), (long) s.pos().z(), s.reason()));
        }
        m.put(KEY_SKIPPED, skipped);
        return m;
    }

    public static JobOutcome fromTree(Object tree) {
        Map<String, Object> m = JsonReads.map(tree, "outcome");
        Set<IntPos> restoreConflicts = new HashSet<>();
        for (Object o : JsonReads.list(m.get(KEY_RESTORE_CONFLICTS), KEY_RESTORE_CONFLICTS)) {
            restoreConflicts.add(posFromTree(o));
        }
        JobOutcome out = new JobOutcome();
        for (Object o : JsonReads.list(m.get(KEY_CONFLICTS), KEY_CONFLICTS)) {
            List<Object> a = JsonReads.list(o, "conflict");
            if (a.size() != CONFLICT_FIELDS) {
                throw new IllegalArgumentException("a conflict has " + CONFLICT_FIELDS + " fields, not " + a.size());
            }
            Conflict c = new Conflict(posFromTree(a.subList(0, POS_FIELDS)),
                    BlockSpecText.parse(JsonReads.string(a.get(3), "expected")),
                    new ObservedBlock(BlockSpecText.parse(JsonReads.string(a.get(4), "observed")),
                            JsonReads.bool(a.get(5), "hasBlockEntity"), JsonReads.string(a.get(6), "beType")),
                    ConflictKind.valueOf(JsonReads.string(a.get(7), "kind")));
            if (restoreConflicts.contains(c.pos())) {
                out.addRestoreConflict(c);
            } else {
                out.addConflict(c);
            }
        }
        for (Object o : JsonReads.list(m.get(KEY_SKIPPED), KEY_SKIPPED)) {
            List<Object> a = JsonReads.list(o, "skipped");
            if (a.size() != SKIPPED_FIELDS) {
                throw new IllegalArgumentException("a skipped placement has " + SKIPPED_FIELDS + " fields, not " + a.size());
            }
            out.skip(new SkippedPlacement(JsonReads.integer(a.get(0), "index"), posFromTree(a.subList(1, POS_FIELDS + 1)),
                    JsonReads.string(a.get(4), "reason")));
        }
        return out;
    }

    private static List<Object> posTree(IntPos p) {
        return List.of((long) p.x(), (long) p.y(), (long) p.z());
    }

    private static IntPos posFromTree(Object o) {
        List<Object> a = JsonReads.list(o, "position");
        if (a.size() != POS_FIELDS) {
            throw new IllegalArgumentException("a position has " + POS_FIELDS + " fields, not " + a.size());
        }
        return new IntPos(JsonReads.integer(a.get(0), "x"), JsonReads.integer(a.get(1), "y"), JsonReads.integer(a.get(2), "z"));
    }
}
