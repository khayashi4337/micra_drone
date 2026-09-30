package io.github.khayashi4337.micradrone.construction.core;

import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Where a debug-command plan comes from (F-22): a bundled sample, or a .json file under the game's micradrone/plans
 * folder. Absolute paths, drive letters, backslashes and any climb out of the folder are refused.
 */
public final class PlanSource {
    public static final String SAMPLE_PREFIX = "sample:";
    public static final String PLANS_DIR = "micradrone/plans";
    public static final int MAX_PLAN_BYTES = 2 * 1024 * 1024;
    public static final Map<String, String> SAMPLES = Map.of("hut", "/data/micradrone/build_samples/hut.json");
    private static final Pattern RELATIVE_JSON = Pattern.compile("[A-Za-z0-9_\\-]+(/[A-Za-z0-9_\\-]+)*\\.json");
    private static final int MAX_SOURCE_CHARS = 128;

    public sealed interface Resolved {
    }

    public record Sample(String resource) implements Resolved {
    }

    public record FileAt(Path path) implements Resolved {
    }

    public record Invalid(String reason) implements Resolved {
    }

    private PlanSource() {
    }

    public static Resolved resolve(String source, Path gameDir) {
        if (source == null || source.isBlank() || source.length() > MAX_SOURCE_CHARS) {
            return new Invalid("empty or too long");
        }
        if (source.startsWith(SAMPLE_PREFIX)) {
            String resource = SAMPLES.get(source.substring(SAMPLE_PREFIX.length()));
            return resource == null ? new Invalid("no such sample: " + source) : new Sample(resource);
        }
        if (!RELATIVE_JSON.matcher(source).matches()) {
            return new Invalid("only a relative .json path of letters, digits, - and _ is allowed");
        }
        Path dir = gameDir.resolve(PLANS_DIR).normalize();
        Path file = dir.resolve(source).normalize();
        return file.startsWith(dir) ? new FileAt(file) : new Invalid("outside " + PLANS_DIR);
    }
}
