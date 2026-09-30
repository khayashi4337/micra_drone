package io.github.khayashi4337.micradrone.construction.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The construction's files under {@code <world>/data/micradrone/} (04 F-2). A write goes to a temp file first, is forced
 * to the disk, and is renamed over the target, so a crash leaves either the old or the new file, never half of one.
 * Where the file system cannot rename atomically, the old file is kept as the previous generation until the new one is
 * in place, and a read takes the newest generation whose seal checks out ({@link SealedFile}: the trailing check value
 * is written last, so it is the commit marker). Paths are relative, slash-separated, and may not climb out of the root.
 */
public final class NioFileSystem implements FileSystemPort {
    public static final String TEMP_MARK = ".tmp-";
    /** The previous generation, kept only while a non-atomic replace is under way. */
    public static final String PREVIOUS_SUFFIX = ".prev";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_\\-.]+(/[A-Za-z0-9_\\-.]+)*");

    /** A hook between writing the temp file and putting it in place; tests use it to play a crash. */
    interface BeforeMove {
        void run() throws IOException;
    }

    /** Forces a file's bytes to the disk; tests record the calls. */
    interface Syncer {
        void sync(Path file) throws IOException;
    }

    // Disk I/O: FileChannel.force(true) flushes the data and the metadata of the temp file before the rename.
    static final Syncer FORCE = file -> {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
    };

    private final Path root;
    private final BeforeMove beforeMove;
    private final boolean atomicMove;
    private final Syncer syncer;

    public NioFileSystem(Path root) {
        this(root, () -> {
        }, true, FORCE);
    }

    NioFileSystem(Path root, BeforeMove beforeMove) {
        this(root, beforeMove, true, FORCE);
    }

    NioFileSystem(Path root, BeforeMove beforeMove, boolean atomicMove, Syncer syncer) {
        this.root = root.toAbsolutePath().normalize();
        this.beforeMove = beforeMove;
        this.atomicMove = atomicMove;
        this.syncer = syncer;
    }

    private Path resolve(String rel) {
        if (!SAFE.matcher(rel).matches() || rel.contains("..")) {
            throw new IllegalArgumentException("not a safe relative path: " + rel);
        }
        Path p = root.resolve(rel).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("outside the construction folder: " + rel);
        }
        return p;
    }

    private static Path previousOf(Path target) {
        return target.resolveSibling(target.getFileName() + PREVIOUS_SUFFIX);
    }

    private static Optional<byte[]> bytesOf(Path p) throws IOException {
        try {
            return Optional.of(Files.readAllBytes(p));
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<byte[]> read(String relPath) throws IOException {
        Path target = resolve(relPath);
        Optional<byte[]> now = bytesOf(target);
        Optional<byte[]> previous = bytesOf(previousOf(target));
        if (previous.isEmpty()) {
            return now;
        }
        // a non-atomic replace was cut short: the new generation counts only once its seal (the commit marker) checks out
        if (now.isPresent() && SealedFile.unseal(now.get()).isPresent()) {
            return now;
        }
        return SealedFile.unseal(previous.get()).isPresent() ? previous : now;
    }

    @Override
    public void writeAtomic(String relPath, byte[] data) throws IOException {
        Path target = resolve(relPath);
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + TEMP_MARK + UUID.randomUUID());
        Files.write(tmp, data);
        syncer.sync(tmp);
        if (atomicMove) {
            beforeMove.run();
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AtomicMoveNotSupportedException e) {
                // fall through to the two-generation replace
            }
        }
        Path previous = previousOf(target);
        if (Files.exists(target)) {
            // the current file becomes the previous generation, unless it is a torn copy left by a cut-short replace
            // (then the previous generation already there is the last committed one and stays)
            boolean committed = !Files.exists(previous) || SealedFile.unseal(Files.readAllBytes(target)).isPresent();
            if (committed) {
                Files.move(target, previous, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (!atomicMove) {
            beforeMove.run();
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        syncer.sync(target);
        Files.deleteIfExists(previous);
    }

    @Override
    public void delete(String relPath) throws IOException {
        Path target = resolve(relPath);
        Files.deleteIfExists(target);
        Files.deleteIfExists(previousOf(target));
    }

    @Override
    public List<String> list(String relDir) throws IOException {
        // directories are named with a trailing slash everywhere ("jobs/", "claims/"); a file path never ends in one, so the
        // safe-path rule for files must not see it
        Path dir = resolve(relDir.endsWith("/") ? relDir.substring(0, relDir.length() - 1) : relDir);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        TreeSet<String> out = new TreeSet<>();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().contains(TEMP_MARK)).toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                // a previous generation stands for its file (the new one may be missing after a cut-short replace)
                out.add(rel.endsWith(PREVIOUS_SUFFIX) ? rel.substring(0, rel.length() - PREVIOUS_SUFFIX.length()) : rel);
            }
        }
        return new ArrayList<>(out);
    }

    public void cleanTemp() throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(p -> p.getFileName().toString().contains(TEMP_MARK)).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
