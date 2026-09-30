package io.github.khayashi4337.micradrone.construction.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/** An in-memory FileSystemPort for the persistence tests. */
public final class InMemoryFileSystem implements FileSystemPort {
    public final TreeMap<String, byte[]> files = new TreeMap<>();

    @Override
    public Optional<byte[]> read(String relPath) {
        byte[] b = files.get(relPath);
        return b == null ? Optional.empty() : Optional.of(b.clone());
    }

    @Override
    public void writeAtomic(String relPath, byte[] data) {
        files.put(relPath, data.clone());
    }

    @Override
    public void delete(String relPath) {
        files.remove(relPath);
    }

    @Override
    public List<String> list(String relDir) {
        List<String> out = new ArrayList<>();
        for (String k : files.keySet()) {
            if (k.startsWith(relDir.endsWith("/") ? relDir : relDir + "/")) {
                out.add(k);
            }
        }
        return out;
    }
}
