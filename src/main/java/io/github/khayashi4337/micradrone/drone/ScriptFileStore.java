package io.github.khayashi4337.micradrone.drone;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads/writes a drone controller's scripts, kept one folder per controller block position (so
 * several scripts can live side by side and be picked from in the GUI) under the world save's
 * {@code micradrone/scripts/} directory. Deliberately Minecraft-free (plain {@link Path}/
 * {@link Files}) so folder naming and seeding behavior can be unit tested.
 */
public final class ScriptFileStore {
    public static final String DEFAULT_SCRIPT_NAME = "main.mdrone";

    private static final Pattern INVALID_FOLDER_CHARS = Pattern.compile("[<>:\"/\\\\|?*\\x00-\\x1F]");
    private static final Set<String> RESERVED_WINDOWS_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private ScriptFileStore() {
    }

    /** The per-controller folder name: one folder per grid position. */
    public static String folderName(int x, int y, int z) {
        return x + "_" + y + "_" + z;
    }

    /**
     * The per-controller folder name when an alias (see {@code DroneControllerBlockEntity#setAlias})
     * is set: the sanitized alias with the coordinate name appended, so renaming stays collision-free
     * even if two controllers end up with the same alias (coordinates are always unique) while still
     * making the folder recognizable at a glance. Falls back to the bare coordinate name - unchanged
     * from before aliases existed - once {@code alias} is blank or sanitizes down to nothing.
     */
    public static String folderName(String alias, int x, int y, int z) {
        String sanitized = sanitizeForFolderName(alias);
        String coordinateName = folderName(x, y, z);
        return sanitized.isEmpty() ? coordinateName : sanitized + "_" + coordinateName;
    }

    /**
     * Strips characters that are invalid in a Windows or POSIX folder name (reserved punctuation,
     * control characters), trims the trailing dots/spaces Windows also rejects, and dodges Windows'
     * reserved device names (CON, PRN, COM1, ...) - all case-insensitively, since a player-typed
     * alias could be anything. Unicode (e.g. Japanese aliases) is left untouched; only the
     * structurally invalid characters are removed. An alias made up entirely of invalid characters
     * collapses to an empty string, which {@link #folderName(String, int, int, int)} treats the same
     * as no alias at all.
     */
    private static String sanitizeForFolderName(String raw) {
        String cleaned = INVALID_FOLDER_CHARS.matcher(raw).replaceAll("").strip();
        while (cleaned.endsWith(".") || cleaned.endsWith(" ")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (RESERVED_WINDOWS_NAMES.contains(cleaned.toUpperCase(Locale.ROOT))) {
            cleaned = cleaned + "_";
        }
        return cleaned;
    }

    /**
     * Resolves this controller's script folder under scriptsDir, creating it if missing and
     * (re-)seeding any of {@link SampleScripts#ALL} that aren't currently present - so the sample
     * scripts act as a permanent reference library that reappears even if deleted. A sample the
     * player has edited in place is left untouched (it still "exists", so it's never overwritten).
     */
    public static Path ensureControllerFolder(Path scriptsDir, int x, int y, int z) throws IOException {
        return ensureControllerFolder(scriptsDir, "", x, y, z);
    }

    /** {@link #ensureControllerFolder(Path, int, int, int)}, but named after {@code alias} (see {@link #folderName(String, int, int, int)}) when one is set. */
    public static Path ensureControllerFolder(Path scriptsDir, String alias, int x, int y, int z) throws IOException {
        Path dir = scriptsDir.resolve(folderName(alias, x, y, z));
        Files.createDirectories(dir);
        for (var entry : SampleScripts.ALL.entrySet()) {
            Path file = dir.resolve(entry.getKey());
            if (!Files.exists(file)) {
                Files.writeString(file, entry.getValue());
            }
        }
        return dir;
    }

    /**
     * Moves a controller's script folder from its old alias-derived name to its new one (see
     * {@code DroneControllerBlockEntity#setAlias}) so existing scripts follow the rename instead of
     * silently being orphaned under the old folder. No-op if the name didn't actually change, if
     * there's no old folder to move (e.g. no script has ever been run there yet - it'll simply get
     * created under the new name on first use), or - defensively, though the coordinate suffix makes
     * this unreachable in practice - if a folder already sits at the destination.
     */
    public static void renameControllerFolder(Path scriptsDir, String oldAlias, String newAlias, int x, int y, int z) throws IOException {
        String oldName = folderName(oldAlias, x, y, z);
        String newName = folderName(newAlias, x, y, z);
        if (oldName.equals(newName)) {
            return;
        }
        Path oldDir = scriptsDir.resolve(oldName);
        Path newDir = scriptsDir.resolve(newName);
        if (Files.isDirectory(oldDir) && !Files.exists(newDir)) {
            Files.move(oldDir, newDir);
        }
    }

    public static String load(Path scriptFile) throws IOException {
        return Files.readString(scriptFile);
    }

    /**
     * True if {@code name} is safe to resolve as a file directly inside a controller's script
     * folder. Script names cross the network from the client (see SaveScriptPayload/
     * RequestScriptSourcePayload), so anything that could escape the folder - path separators,
     * parent-directory hops, drive-letter colons - is rejected, as is anything that isn't a
     * non-empty {@code *.mdrone} name.
     */
    public static boolean isValidScriptName(String name) {
        return name != null
                && name.endsWith(".mdrone")
                && name.length() > ".mdrone".length()
                && !name.contains("/")
                && !name.contains("\\")
                && !name.contains("..")
                && !name.contains(":");
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

    /** {@link #listScripts}, but paired with each file's {@link #describeScript} result for display. */
    public static Map<String, String> listScriptsWithDescriptions(Path controllerFolder) throws IOException {
        Map<String, String> descriptions = new LinkedHashMap<>();
        for (String name : listScripts(controllerFolder)) {
            descriptions.put(name, describeScript(load(controllerFolder.resolve(name)), name));
        }
        return descriptions;
    }

    /**
     * The script's first non-empty "#" comment line (leading blank lines are skipped), stripped of
     * the "#" and surrounding whitespace - a raw file name like "till_and_plant.mdrone" says a lot
     * less than the comment a script's author already wrote at the top of it. Falls back to
     * {@code fallbackName} once real code is reached without finding a usable comment.
     */
    public static String describeScript(String content, String fallbackName) {
        for (String rawLine : content.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                String description = line.substring(1).strip();
                if (!description.isEmpty()) {
                    return description;
                }
                continue;
            }
            break;
        }
        return fallbackName;
    }
}
