package io.github.khayashi4337.micradrone.drone;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads/writes a drone controller's script file, one per controller block position, under the
 * world save's {@code micradrone/scripts/} directory. Deliberately Minecraft-free (plain
 * {@link Path}/{@link Files}) so file naming and default-content behavior can be unit tested.
 */
public final class ScriptFileStore {
    private static final String DEFAULT_SCRIPT = """
            # Write your drone script here, then click Run.
            print(get_world_size())
            """;

    private ScriptFileStore() {
    }

    public static String fileName(int x, int y, int z) {
        return x + "_" + y + "_" + z + ".mdrone";
    }

    /** Reads the script file for (x,y,z) under scriptsDir, creating it with default content if missing. */
    public static String loadOrCreateDefault(Path scriptsDir, int x, int y, int z) throws IOException {
        Path file = scriptsDir.resolve(fileName(x, y, z));
        if (!Files.exists(file)) {
            Files.createDirectories(scriptsDir);
            Files.writeString(file, DEFAULT_SCRIPT);
            return DEFAULT_SCRIPT;
        }
        return Files.readString(file);
    }
}
