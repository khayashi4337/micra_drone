package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class WalCodecTest {
    private static final List<WalEntry> ALL = List.of(
            new WalEntry.RunStart("job-1", 7),
            new WalEntry.PlaceIntent("job-1", 7, new JournalRecord(3, new IntPos(1, 64, 2), BlockSpec.of("minecraft:grass_block"),
                    false, BlockSpec.of("minecraft:oak_stairs", "facing", "north"), 3, true)),
            new WalEntry.MaterialIntent("job-1", 7, new OpKey("job-1", 3, MaterialOp.CHARGE),
                    List.of(new ItemCount("minecraft:oak_stairs", 1))),
            new WalEntry.DropIntent("job-1", 7, new IntPos(2, 64, 2)),
            new WalEntry.RunEnd("job-1", 7, List.of(3), List.of(new WalEntry.OpMoves(new OpKey("job-1", 3, MaterialOp.CHARGE),
                    List.of(new Move("chest:5,64,5", "minecraft:oak_stairs", -1))))),
            new WalEntry.DurablePoint(7));

    private static byte[] file(List<WalEntry> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (WalEntry e : entries) {
            out.writeBytes(WalCodec.frame(e));
        }
        return out.toByteArray();
    }

    @Test
    void everyKindOfEntryRoundTripsThroughItsFrame() {
        byte[] f = file(ALL);
        WalCodec.Frames back = WalCodec.readFrames(f);
        assertEquals(ALL, back.entries());
        assertEquals(f.length, back.validLength());
    }

    @Test
    void aTornLastFrameIsDroppedAndItsStartIsReported() {
        byte[] whole = file(ALL);
        int goodPart = file(ALL.subList(0, ALL.size() - 1)).length;
        for (int cut = goodPart; cut < whole.length; cut++) {
            WalCodec.Frames back = WalCodec.readFrames(Arrays.copyOf(whole, cut));
            assertEquals(ALL.subList(0, ALL.size() - 1), back.entries(), "cut at " + cut);
            assertEquals(goodPart, back.validLength(), "the writer cuts the file back to here before appending");
        }
        // damage in the middle: nothing after it is trusted (the runs must be folded in order)
        int twoFrames = file(ALL.subList(0, 2)).length;
        byte[] flipped = whole.clone();
        flipped[twoFrames + WalCodec.LENGTH_BYTES + SealedFile.MAGIC.length + 1] ^= 1;
        WalCodec.Frames damaged = WalCodec.readFrames(flipped);
        assertEquals(ALL.subList(0, 2), damaged.entries(), "a damaged frame ends the log");
        assertEquals(twoFrames, damaged.validLength());
    }
}
