package io.github.khayashi4337.micradrone.construction.core;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * The construction's durable bytes (04 F-2). Paths are relative, slash-separated, and may not climb out of the
 * implementation's root; an escaping path is an {@link IllegalArgumentException}. {@link #writeAtomic} replaces a
 * file atomically: a crash leaves either the old or the new content, never half of either.
 */
public interface FileSystemPort {
    /** The file's bytes, or empty if it does not exist. */
    Optional<byte[]> read(String relPath) throws IOException;

    /** Replaces the file's whole content atomically (temp file, forced to the disk, then renamed over it). */
    void writeAtomic(String relPath, byte[] data) throws IOException;

    /** Removes the file (and any not-yet-committed previous generation of it); a missing file is not an error. */
    void delete(String relPath) throws IOException;

    /** The relative paths of every regular file under {@code relDir}, sorted; empty if the directory is absent. */
    List<String> list(String relDir) throws IOException;
}
