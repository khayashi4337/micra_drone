package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.CanonicalJson;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The write-ahead log's entries as trees, and its file as frames: each frame is a 4-byte big-endian length and a
 * {@link SealedFile} of the entry's canonical JSON. The log file is only ever appended to, so a crash can only tear its
 * last frame; reading stops at the first frame that is short or fails its check value, and reports where the good part
 * ends so the writer can cut the torn tail off before it appends again (Task 25).
 */
public final class WalCodec {
    public static final int LENGTH_BYTES = Integer.BYTES;
    /** No single entry comes near this; a larger length can only be a torn or foreign file. */
    public static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;
    static final String KEY_TYPE = "t";
    static final String T_START = "start";
    static final String T_PLACE = "place";
    static final String T_MATERIAL = "material";
    static final String T_DROP = "drop";
    static final String T_END = "end";
    static final String T_DURABLE = "durable";

    /** The entries of the good part of a log file, and that part's length in bytes. */
    public record Frames(List<WalEntry> entries, long validLength) {
        public Frames {
            entries = List.copyOf(entries);
        }
    }

    private WalCodec() {
    }

    public static byte[] frame(WalEntry e) {
        byte[] sealed = SealedFile.seal(CanonicalJson.write(toTree(e)).getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.allocate(LENGTH_BYTES + sealed.length).putInt(sealed.length).put(sealed).array();
    }

    public static Frames readFrames(byte[] file) {
        List<WalEntry> out = new ArrayList<>();
        int at = 0;
        while (file.length - at >= LENGTH_BYTES) {
            int len = ByteBuffer.wrap(file, at, LENGTH_BYTES).getInt();
            if (len <= 0 || len > MAX_FRAME_BYTES || file.length - at - LENGTH_BYTES < len) {
                break;
            }
            Optional<byte[]> body = SealedFile.unseal(Arrays.copyOfRange(file, at + LENGTH_BYTES, at + LENGTH_BYTES + len));
            if (body.isEmpty()) {
                break;
            }
            try {
                out.add(fromTree(MiniJson.parse(new String(body.get(), StandardCharsets.UTF_8))));
            } catch (IllegalArgumentException e) {
                break;
            }
            at += LENGTH_BYTES + len;
        }
        return new Frames(out, at);
    }

    public static Object toTree(WalEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (e) {
            case WalEntry.RunStart s -> {
                m.put(KEY_TYPE, T_START);
                m.put("job", s.jobId());
                m.put("run", s.run());
            }
            case WalEntry.PlaceIntent p -> {
                m.put(KEY_TYPE, T_PLACE);
                m.put("job", p.jobId());
                m.put("run", p.run());
                m.put("record", JournalCodec.recordTree(p.record()));
            }
            case WalEntry.MaterialIntent mi -> {
                m.put(KEY_TYPE, T_MATERIAL);
                m.put("job", mi.jobId());
                m.put("run", mi.run());
                m.put("key", keyTree(mi.key()));
                m.put("items", LedgerCodec.itemsTree(mi.items()));
            }
            case WalEntry.DropIntent d -> {
                m.put(KEY_TYPE, T_DROP);
                m.put("job", d.jobId());
                m.put("run", d.run());
                m.put("pos", List.of((long) d.pos().x(), (long) d.pos().y(), (long) d.pos().z()));
            }
            case WalEntry.RunEnd end -> {
                m.put(KEY_TYPE, T_END);
                m.put("job", end.jobId());
                m.put("run", end.run());
                List<Object> written = new ArrayList<>();
                end.written().forEach(i -> written.add((long) i));
                m.put("written", written);
                List<Object> moves = new ArrayList<>();
                for (WalEntry.OpMoves om : end.moves()) {
                    List<Object> ms = new ArrayList<>();
                    for (Move mv : om.moves()) {
                        ms.add(List.of(mv.sourceId(), mv.itemId(), (long) mv.delta()));
                    }
                    moves.add(List.of(keyTree(om.key()), ms));
                }
                m.put("moves", moves);
            }
            case WalEntry.DurablePoint d -> {
                m.put(KEY_TYPE, T_DURABLE);
                m.put("upTo", d.upToRun());
            }
        }
        return m;
    }

    public static WalEntry fromTree(Object tree) {
        Map<String, Object> m = JsonReads.map(tree, "log entry");
        String t = JsonReads.string(m.get(KEY_TYPE), KEY_TYPE);
        return switch (t) {
            case T_START -> new WalEntry.RunStart(str(m, "job"), run(m, "run"));
            case T_PLACE -> new WalEntry.PlaceIntent(str(m, "job"), run(m, "run"), JournalCodec.recordFromTree(m.get("record")));
            case T_MATERIAL -> new WalEntry.MaterialIntent(str(m, "job"), run(m, "run"), keyFromTree(m.get("key")),
                    LedgerCodec.itemsFromTree(m.get("items")));
            case T_DROP -> {
                List<Object> p = JsonReads.list(m.get("pos"), "pos");
                yield new WalEntry.DropIntent(str(m, "job"), run(m, "run"), new IntPos(JsonReads.integer(p.get(0), "x"),
                        JsonReads.integer(p.get(1), "y"), JsonReads.integer(p.get(2), "z")));
            }
            case T_END -> {
                List<Integer> written = new ArrayList<>();
                for (Object o : JsonReads.list(m.get("written"), "written")) {
                    written.add(JsonReads.integer(o, "written index"));
                }
                List<WalEntry.OpMoves> moves = new ArrayList<>();
                for (Object o : JsonReads.list(m.get("moves"), "moves")) {
                    List<Object> pair = JsonReads.list(o, "op moves");
                    List<Move> ms = new ArrayList<>();
                    for (Object x : JsonReads.list(pair.get(1), "moves")) {
                        List<Object> a = JsonReads.list(x, "move");
                        ms.add(new Move(JsonReads.string(a.get(0), "source"), JsonReads.string(a.get(1), "item"),
                                JsonReads.integer(a.get(2), "delta")));
                    }
                    moves.add(new WalEntry.OpMoves(keyFromTree(pair.get(0)), ms));
                }
                yield new WalEntry.RunEnd(str(m, "job"), run(m, "run"), written, moves);
            }
            case T_DURABLE -> new WalEntry.DurablePoint(run(m, "upTo"));
            default -> throw new IllegalArgumentException("unknown log entry " + t);
        };
    }

    private static String str(Map<String, Object> m, String k) {
        return JsonReads.string(m.get(k), k);
    }

    private static long run(Map<String, Object> m, String k) {
        return JsonReads.longValue(m.get(k), k);
    }

    private static List<Object> keyTree(OpKey k) {
        return List.of(k.jobId(), (long) k.ledgerKey(), k.op().name());
    }

    private static OpKey keyFromTree(Object o) {
        List<Object> a = JsonReads.list(o, "op key");
        return new OpKey(JsonReads.string(a.get(0), "job"), JsonReads.integer(a.get(1), "ledger key"),
                MaterialOp.valueOf(JsonReads.string(a.get(2), "op")));
    }
}
