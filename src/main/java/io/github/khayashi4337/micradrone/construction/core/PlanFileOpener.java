package io.github.khayashi4337.micradrone.construction.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

/**
 * Reads a plan file from the plans folder only (F-22). A normalized path can still leave the folder through a link, so
 * every element from the folder down to the file must be a plain directory or file (no symbolic link, no Windows junction
 * or other reparse point, which Java reports as "other"), the real path must stay under the folder's real path, and the
 * file is opened without following links. File access is kept here, apart from the pure {@link PlanSource} checks.
 */
public final class PlanFileOpener {
    /** What the checks need to know about a path; the default reads the real file system. */
    public interface PathFacts {
        /** A symbolic link, a junction or another reparse point (anything but a plain file or directory). */
        boolean isLinkLike(Path p) throws IOException;

        Path realPath(Path p) throws IOException;
    }

    public static final PathFacts NIO = new PathFacts() {
        @Override
        public boolean isLinkLike(Path p) throws IOException {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return a.isSymbolicLink() || a.isOther();
        }

        @Override
        public Path realPath(Path p) throws IOException {
            return p.toRealPath();
        }
    };

    private PlanFileOpener() {
    }

    /** Empty when the path is safe to open; otherwise why not. */
    public static Optional<String> check(Path plansDir, Path file, PathFacts facts) throws IOException {
        Path dir = plansDir.toAbsolutePath().normalize();
        Path target = file.toAbsolutePath().normalize();
        if (!target.startsWith(dir) || target.equals(dir)) {
            return Optional.of("outside the plans folder");
        }
        for (Path p = target; p != null && p.startsWith(dir); p = p.getParent()) {
            if (facts.isLinkLike(p)) {
                return Optional.of("a link or junction on the way: " + dir.relativize(p));
            }
        }
        if (!facts.realPath(target).startsWith(facts.realPath(dir))) {
            return Optional.of("the real file is outside the plans folder");
        }
        return Optional.empty();
    }

    /** The file's bytes, at most {@code maxBytes}; refused with an IOException when unsafe or too big. */
    public static byte[] read(Path plansDir, Path file, int maxBytes) throws IOException {
        Optional<String> why = check(plansDir, file, NIO);
        if (why.isPresent()) {
            throw new IOException(why.get());
        }
        try (SeekableByteChannel ch = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (ch.size() > maxBytes) {
                throw new IOException("the plan is larger than " + maxBytes + " bytes");
            }
            ByteBuffer buf = ByteBuffer.allocate((int) ch.size());
            while (buf.hasRemaining() && ch.read(buf) >= 0) {
                // keep reading until the file is in
            }
            return buf.array();
        }
    }
}
