package io.github.khayashi4337.micradrone.drone;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads/writes a drone controller's scripts, kept one folder per controller block position (so
 * several scripts can live side by side and be picked from in the GUI) under the world save's
 * {@code micradrone/scripts/} directory. Deliberately Minecraft-free (plain {@link Path}/
 * {@link Files}) so folder naming and seeding behavior can be unit tested.
 */
public final class ScriptFileStore {
    public static final String DEFAULT_SCRIPT_NAME = "main.mdrone";

    private ScriptFileStore() {
    }

    /** The per-controller folder name: one folder per grid position. */
    public static String folderName(int x, int y, int z) {
        return x + "_" + y + "_" + z;
    }

    /**
     * Resolves this controller's script folder under scriptsDir, creating it if missing and
     * (re-)seeding any of {@link SampleScripts#ALL} that aren't currently present - so the sample
     * scripts act as a permanent reference library that reappears even if deleted. A sample the
     * player has edited in place is left untouched (it still "exists", so it's never overwritten).
     */
    public static Path ensureControllerFolder(Path scriptsDir, int x, int y, int z) throws IOException {
        Path dir = scriptsDir.resolve(folderName(x, y, z));
        Files.createDirectories(dir);
        for (var entry : SampleScripts.ALL.entrySet()) {
            Path file = dir.resolve(entry.getKey());
            if (!Files.exists(file)) {
                Files.writeString(file, entry.getValue());
            }
        }
        return dir;
    }

    public static String load(Path scriptFile) throws IOException {
        return Files.readString(scriptFile);
    }

    /** Every ".mdrone" file name (with extension) currently in a controller's folder, sorted. */
    public static List<String> listScripts(Path controllerFolder) throws IOException {
        if (!Files.isDirectory(controllerFolder)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(controllerFolder)) {
            List<String> names = new ArrayList<>();
            entries.filter(p -> p.getFileName().toString().endsWith(".mdrone"))
                    .forEach(p -> names.add(p.getFileName().toString()));
            names.sort(String::compareTo);
            return names;
        }
    }
}
