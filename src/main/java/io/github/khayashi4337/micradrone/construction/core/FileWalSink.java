package io.github.khayashi4337.micradrone.construction.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * The write-ahead log's files ({@code <world>/data/micradrone/wal/seg-NNNNNNNN.bin}): frames ({@link WalCodec}) appended
 * to the newest segment and forced to the disk before {@link #write} returns. A durable point starts a new segment
 * holding only that point and deletes the older ones, so the log never grows past the runs since the last checkpoint. A
 * crash can only tear the last frame of the newest segment; {@link #readAll} cuts it back to its good part. Disk I/O
 * only: the rules are in WalCodec and WalRecovery.
 */
public final class FileWalSink implements WriteAheadLog.Sink {
    static final String PREFIX = "seg-";
    static final String SUFFIX = ".bin";
    static final String NUMBER_FORMAT = "%08d";

    private final Path dir;
    private volatile WriteAheadLog.Barrier barrier;

    public FileWalSink(Path dir) {
        this(dir, WriteAheadLog.Barrier.NONE);
    }

    /** With a barrier (development only): each batch is written in two halves with MID_FRAME in between. */
    public FileWalSink(Path dir, WriteAheadLog.Barrier barrier) {
        this.dir = dir.toAbsolutePath().normalize();
        this.barrier = barrier;
    }

    /**
     * The runtime's dev-barrier hook (Task 25): the sink is opened before the devkit can arm a boundary, so the
     * barrier arrives after construction. {@code null} and {@link WriteAheadLog.Barrier#NONE} both restore the
     * single-append write.
     */
    public void setBarrier(WriteAheadLog.Barrier barrier) {
        this.barrier = barrier == null ? WriteAheadLog.Barrier.NONE : barrier;
    }

    private List<Path> segments() throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(PREFIX) && p.getFileName().toString().endsWith(SUFFIX))
                    .sorted().toList();
        }
    }

    private Path segment(long n) {
        return dir.resolve(PREFIX + String.format(NUMBER_FORMAT, n) + SUFFIX);
    }

    private static long numberOf(Path p) {
        String n = p.getFileName().toString();
        return Long.parseLong(n.substring(PREFIX.length(), n.length() - SUFFIX.length()));
    }

    private static byte[] frames(List<WalEntry> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (WalEntry e : entries) {
            out.writeBytes(WalCodec.frame(e));
        }
        return out.toByteArray();
    }

    private static void append(Path file, byte[] bytes) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            // the length is metadata: force(true), or a power cut can leave the frames written but not reachable
            ch.force(true);
        }
    }

    @Override
    public void write(List<WalEntry> entries) {
        try {
            Files.createDirectories(dir);
            List<Path> segs = segments();
            Path target = segs.isEmpty() ? segment(1) : segs.get(segs.size() - 1);
            byte[] bytes = frames(entries);
            WriteAheadLog.Barrier b = barrier;
            if (b == WriteAheadLog.Barrier.NONE) {
                append(target, bytes);
            } else {
                append(target, Arrays.copyOf(bytes, bytes.length / 2));
                b.at(WriteAheadLog.Barrier.MID_FRAME);
                append(target, Arrays.copyOfRange(bytes, bytes.length / 2, bytes.length));
            }
        } catch (IOException e) {
            // nothing may change in the world without its intent on the disk: the caller stops construction
            throw new UncheckedIOException("the write-ahead log could not be made durable: " + dir, e);
        }
    }

    @Override
    public List<WalEntry> readAll() {
        try {
            List<Path> segs = segments();
            List<WalEntry> out = new ArrayList<>();
            for (int i = 0; i < segs.size(); i++) {
                byte[] bytes = Files.readAllBytes(segs.get(i));
                WalCodec.Frames f = WalCodec.readFrames(bytes);
                if (f.validLength() < bytes.length) {
                    if (i < segs.size() - 1) {
                        throw new UncheckedIOException(new IOException("a closed log segment is damaged: " + segs.get(i)));
                    }
                    try (FileChannel ch = FileChannel.open(segs.get(i), StandardOpenOption.WRITE)) {
                        ch.truncate(f.validLength());
                        ch.force(true);
                    }
                }
                out.addAll(f.entries());
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("the write-ahead log could not be read: " + dir, e);
        }
    }

    @Override
    public void dropUpTo(long run) {
        try {
            List<Path> segs = segments();
            long next = segs.isEmpty() ? 1 : numberOf(segs.get(segs.size() - 1)) + 1;
            // the new segment first (holding the durable point, so the run numbers go on), then the old ones go
            append(segment(next), frames(List.of(new WalEntry.DurablePoint(run))));
            for (Path p : segs) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("the write-ahead log could not be cut at the durable point: " + dir, e);
        }
    }
}
