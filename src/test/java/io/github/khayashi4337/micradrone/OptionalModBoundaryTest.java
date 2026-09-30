package io.github.khayashi4337.micradrone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Create, Aeronautics and Sable are OPTIONAL dependencies ({@code type="optional"} in
 * {@code neoforge.mods.toml}): the game must start without them. FML does not guard optional
 * dependencies, so any code that touches their classes must live in the dedicated integration
 * package {@code io.github.khayashi4337.micradrone.integration} (loaded only after the mod is
 * confirmed present, F-11/D-13). Everything else may never import or qualify-reference a class of
 * {@code com.simibubi.*} (Create), {@code net.createmod.*} (Ponder), {@code dev.engine_room.*}
 * (Flywheel), {@code com.tterrag.*} (Registrate), {@code dev.eriksonn.*} (Aeronautics),
 * {@code dev.simulated_team.*} (simulated) or {@code dev.ryanhcode.*} (sable, offroad) - that would
 * be a {@link NoClassDefFoundError} for a player without the mods.
 */
class OptionalModBoundaryTest {
    private static final Path ROOT = Path.of("src/main/java/io/github/khayashi4337/micradrone");
    private static final Path TOML = Path.of("src/main/templates/META-INF/neoforge.mods.toml");
    /** The only package that may touch the optional mods (F-11). */
    private static final String INTEGRATION_PACKAGE = "integration";
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(com\\.simibubi|net\\.createmod|dev\\.engine_room|com\\.tterrag|dev\\.eriksonn"
                    + "|dev\\.simulated_team|dev\\.ryanhcode)\\.[a-z]");

    private static boolean isIntegration(Path file) {
        String rel = ROOT.relativize(file).toString().replace('\\', '/');
        return rel.startsWith(INTEGRATION_PACKAGE + "/");
    }

    /** Violations of one file: imports or qualified uses of the optional mods (comments excluded). */
    static List<String> violations(Path file, String text) {
        List<String> out = new ArrayList<>();
        int n = 0;
        for (String line : text.split("\n", -1)) {
            n++;
            String trimmed = line.trim();
            if (!trimmed.startsWith("*") && !trimmed.startsWith("//") && FORBIDDEN.matcher(line).find()) {
                out.add(file + ":" + n + ": " + trimmed);
            }
        }
        return out;
    }

    @Test
    void theThreeModsAreOptionalInTheModsToml() throws IOException {
        String toml = Files.readString(TOML, StandardCharsets.UTF_8);
        for (String mod : List.of("create", "aeronautics", "sable")) {
            Pattern decl = Pattern.compile(
                    "modId=\"" + mod + "\"\\s*\\R\\s*type=\"(\\w+)\"");
            var m = decl.matcher(toml);
            assertTrue(m.find(), mod + " is not declared in neoforge.mods.toml");
            assertEquals("optional", m.group(1), mod + " must be an optional dependency (F-11)");
        }
    }

    @Test
    void onlyTheIntegrationPackageImportsTheOptionalMods() throws IOException {
        List<String> all = new ArrayList<>();
        try (Stream<Path> s = Files.walk(ROOT)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (!isIntegration(p)) {
                    all.addAll(violations(p, Files.readString(p, StandardCharsets.UTF_8)));
                }
            }
        }
        assertEquals(List.of(), all);
    }

    @Test
    void theGuardRecognisesEveryOptionalModNamespace() {
        for (String q : List.of("import com.simibubi.create.AllBlocks;\n",
                "import net.createmod.ponder.PonderScene;\n",
                "import dev.engine_room.flywheel.api.Foo;\n",
                "import com.tterrag.registrate.Registrate;\n",
                "import dev.eriksonn.aeronautics.Ship;\n",
                "import dev.simulated_team.simulated.X;\n",
                "import dev.ryanhcode.sable.Lib;\n",
                "import dev.ryanhcode.offroad.Wheel;\n",
                "var b = com.simibubi.create.AllBlocks.SHAFT;\n")) {
            assertEquals(1, violations(Path.of("X.java"), q).size(), q);
        }
        assertEquals(0, violations(Path.of("X.java"),
                "// mentions com.simibubi.create in a comment\n import java.util.List;\n").size());
    }
}
