package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileWalSinkTest {
    @TempDir
    Path dir;

    private List<Path> segments() throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().toList();
        }
    }

    @Test
    void appendedBatchesComeBackInOrderAfterAReopen() {
        FileWalSink sink = new FileWalSink(dir);
        sink.write(List.of(new WalEntry.RunStart("job-1", 1), new WalEntry.RunEnd("job-1", 1, List.of(), List.of())));
        sink.write(List.of(new WalEntry.RunStart("job-1", 2)));
        assertEquals(List.of(new WalEntry.RunStart("job-1", 1), new WalEntry.RunEnd("job-1", 1, List.of(), List.of()),
                new WalEntry.RunStart("job-1", 2)), new FileWalSink(dir).readAll());
    }

    @Test
    void aTornTailIsCutOffSoTheNextAppendIsReadable() throws IOException {
        FileWalSink sink = new FileWalSink(dir);
        sink.write(List.of(new WalEntry.RunStart("job-1", 1)));
        Path seg = segments().get(0);
        long good = Files.size(seg);
        // a crash in the middle of the next append: half a frame is on the disk
        byte[] half = WalCodec.frame(new WalEntry.RunStart("job-1", 2));
        Files.write(seg, java.util.Arrays.copyOf(half, half.length / 2), StandardOpenOption.APPEND);
        FileWalSink reopened = new FileWalSink(dir);
        assertEquals(List.of(new WalEntry.RunStart("job-1", 1)), reopened.readAll());
        assertEquals(good, Files.size(seg), "cut back to the good part");
        reopened.write(List.of(new WalEntry.RunStart("job-1", 2)));
        assertEquals(List.of(new WalEntry.RunStart("job-1", 1), new WalEntry.RunStart("job-1", 2)), new FileWalSink(dir).readAll(),
                "without the cut, this entry would sit behind garbage for ever");
    }

    @Test
    void aDamagedClosedSegmentIsRefusedInsteadOfCutBack() throws IOException {
        FileWalSink sink = new FileWalSink(dir);
        sink.write(List.of(new WalEntry.RunStart("job-1", 1)));
        // a second segment, so the first is closed: only the newest may carry a torn tail
        Files.write(dir.resolve(FileWalSink.PREFIX + String.format(FileWalSink.NUMBER_FORMAT, 2) + FileWalSink.SUFFIX),
                WalCodec.frame(new WalEntry.DurablePoint(1)));
        Path closed = segments().get(0);
        // a length that promises five bytes of body, one junk byte on the disk: damage, not a crash's torn tail
        Files.write(closed, new byte[]{0, 0, 0, 5, 'x'}, StandardOpenOption.APPEND);
        assertThrows(UncheckedIOException.class, () -> new FileWalSink(dir).readAll(),
                "a closed segment must be whole; cutting it back would quietly drop logged runs");
    }

    @Test
    void aDurablePointStartsANewSegmentAndDeletesTheOldOnes() throws IOException {
        FileWalSink sink = new FileWalSink(dir);
        List<WalEntry> many = new ArrayList<>();
        for (int run = 1; run <= 5; run++) {
            many.add(new WalEntry.RunStart("job-1", run));
        }
        sink.write(many);
        WriteAheadLog wal = WriteAheadLog.open(sink);
        wal.markDurable(wal.lastRun());
        assertEquals(1, segments().size(), "bounded: only the runs since the last checkpoint are kept");
        assertEquals(List.of(new WalEntry.DurablePoint(5)), new FileWalSink(dir).readAll());
        WriteAheadLog reopened = WriteAheadLog.open(new FileWalSink(dir));
        assertEquals(6, reopened.newRun());
        assertEquals(5, reopened.durableUpTo());
    }
}
