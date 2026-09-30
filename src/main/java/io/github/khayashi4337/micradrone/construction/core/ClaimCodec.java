package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.Box;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The claim book as a tree: a list of claims
 * {@code [{"v": n, "id": s, "owner": s, "dimension": s, "world": [6], "operating": [6], "released": b, "createdTick": n}]}.
 * The per-owner limit is configuration, not saved data: it is handed to {@link #fromTree} instead.
 */
public final class ClaimCodec {
    private static final String KEY_SCHEMA_VERSION = "v";
    private static final String KEY_ID = "id";
    private static final String KEY_OWNER = "owner";
    private static final String KEY_DIMENSION = "dimension";
    private static final String KEY_WORLD = "world";
    private static final String KEY_OPERATING = "operating";
    private static final String KEY_RELEASED = "released";
    private static final String KEY_CREATED_TICK = "createdTick";
    private static final int BOX_FIELDS = 6;

    private ClaimCodec() {
    }

    public static Object toTree(ClaimBook book) {
        List<Object> out = new ArrayList<>();
        for (SiteClaim c : book.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(KEY_SCHEMA_VERSION, (long) c.schemaVersion());
            m.put(KEY_ID, c.claimId());
            m.put(KEY_OWNER, c.ownerUuid().toString());
            m.put(KEY_DIMENSION, c.dimension());
            m.put(KEY_WORLD, boxTree(c.worldBox()));
            m.put(KEY_OPERATING, boxTree(c.operatingBox()));
            m.put(KEY_RELEASED, c.released());
            m.put(KEY_CREATED_TICK, c.createdTick());
            out.add(m);
        }
        return out;
    }

    public static ClaimBook fromTree(Object tree, int maxPerOwner) {
        ClaimBook book = new ClaimBook(maxPerOwner);
        for (Object o : JsonReads.list(tree, "claims")) {
            Map<String, Object> m = JsonReads.map(o, "claim");
            book.restore(new SiteClaim(JsonReads.integer(m.get(KEY_SCHEMA_VERSION), KEY_SCHEMA_VERSION),
                    JsonReads.string(m.get(KEY_ID), KEY_ID),
                    UUID.fromString(JsonReads.string(m.get(KEY_OWNER), KEY_OWNER)),
                    JsonReads.string(m.get(KEY_DIMENSION), KEY_DIMENSION),
                    boxFromTree(m.get(KEY_WORLD)), boxFromTree(m.get(KEY_OPERATING)),
                    JsonReads.bool(m.get(KEY_RELEASED), KEY_RELEASED),
                    JsonReads.longValue(m.get(KEY_CREATED_TICK), KEY_CREATED_TICK)));
        }
        return book;
    }

    private static List<Object> boxTree(Box b) {
        return List.of((long) b.minA(), (long) b.minB(), (long) b.minC(), (long) b.maxA(), (long) b.maxB(), (long) b.maxC());
    }

    private static Box boxFromTree(Object o) {
        List<Object> a = JsonReads.list(o, "box");
        if (a.size() != BOX_FIELDS) {
            throw new IllegalArgumentException("a box has " + BOX_FIELDS + " fields, not " + a.size());
        }
        return new Box(JsonReads.integer(a.get(0), "minA"), JsonReads.integer(a.get(1), "minB"),
                JsonReads.integer(a.get(2), "minC"), JsonReads.integer(a.get(3), "maxA"),
                JsonReads.integer(a.get(4), "maxB"), JsonReads.integer(a.get(5), "maxC"));
    }
}
