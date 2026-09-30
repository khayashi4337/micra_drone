package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.List;

/**
 * The journal as a tree: {@code [[index, x, y, z, "before", hadBlockEntity, "placed", ledgerKey, terrainCut], ...]}. One
 * record's form is shared with the write-ahead log's place intents ({@link WalCodec}).
 */
public final class JournalCodec {
    private static final int RECORD_FIELDS = 9;

    private JournalCodec() {
    }

    public static Object toTree(Journal journal) {
        List<Object> out = new ArrayList<>();
        for (JournalRecord r : journal.records()) {
            out.add(recordTree(r));
        }
        return out;
    }

    /** Every record is added again; the journal's size is set from its own maximum, not from the file. */
    public static Journal fromTree(Object tree, int maxEntries) {
        Journal j = new Journal(maxEntries);
        for (Object o : JsonReads.list(tree, "journal")) {
            j.record(recordFromTree(o));
        }
        return j;
    }

    public static Journal fromTree(Object tree) {
        return fromTree(tree, Journal.MAX_ENTRIES);
    }

    static List<Object> recordTree(JournalRecord r) {
        return List.of((long) r.placementIndex(), (long) r.pos().x(), (long) r.pos().y(), (long) r.pos().z(), r.before().toString(),
                r.beforeHadBlockEntity(), r.placed().toString(), (long) r.ledgerKey(), r.terrainCut());
    }

    static JournalRecord recordFromTree(Object o) {
        List<Object> a = JsonReads.list(o, "journal record");
        if (a.size() != RECORD_FIELDS) {
            throw new IllegalArgumentException("a journal record has " + RECORD_FIELDS + " fields, not " + a.size());
        }
        return new JournalRecord(JsonReads.integer(a.get(0), "index"),
                new IntPos(JsonReads.integer(a.get(1), "x"), JsonReads.integer(a.get(2), "y"), JsonReads.integer(a.get(3), "z")),
                BlockSpecText.parse(JsonReads.string(a.get(4), "before")), JsonReads.bool(a.get(5), "hadBlockEntity"),
                BlockSpecText.parse(JsonReads.string(a.get(6), "placed")), JsonReads.integer(a.get(7), "ledgerKey"),
                JsonReads.bool(a.get(8), "terrainCut"));
    }
}
