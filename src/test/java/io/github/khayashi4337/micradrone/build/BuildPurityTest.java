package io.github.khayashi4337.micradrone.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * D-16: the pure core ({@code build.*}) and the language ({@code lang}, {@code lang.ast}) must not touch
 * Minecraft, and the packages inside them must keep the layering that was measured on the import graph:
 * {@code build.model} and {@code lang.ast} depend on nothing internal; {@code build.parts},
 * {@code build.compile.gen} see {@code build.model}/{@code build.parts}; {@code lang} sees
 * {@code lang.ast}, {@code build.model}, {@code build.parts}; {@code build.plan} adds {@code lang};
 * {@code build.compile} adds {@code build.plan} and {@code build.compile.gen}; {@code build.script}
 * sits on top. A new direction is a deliberate design change, so this test fails loudly on it.
 */
class BuildPurityTest {
    private static final Path ROOT = Path.of("src/main/java/io/github/khayashi4337/micradrone");
    /** Packages that must stay free of the game and of each other's internals beyond the pinned edges. */
    private static final Set<String> PURE_PREFIXES = Set.of("build", "lang");
    private static final Pattern FORBIDDEN_IMPORT = Pattern.compile(
            "^\\s*import\\s+(static\\s+)?(net\\.minecraft|net\\.neoforged|com\\.mojang|com\\.simibubi"
                    + "|net\\.createmod|dev\\.engine_room)\\.");
    private static final Pattern FORBIDDEN_QUALIFIED = Pattern.compile(
            "\\b(net\\.minecraft|net\\.neoforged|com\\.mojang|com\\.simibubi|net\\.createmod"
                    + "|dev\\.engine_room)\\.[a-z]");
    private static final Pattern INTERNAL_IMPORT = Pattern.compile(
            "^\\s*import\\s+(static\\s+)?io\\.github\\.khayashi4337\\.micradrone\\.([A-Za-z0-9_.]+)");

    /**
     * The measured edges. Every package of {@code build.*}/{@code lang*} must appear as a key; the value is the
     * exact set of internal packages it may import (itself excluded - a package may always use itself).
     */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "build.model", Set.of(),
            "build.parts", Set.of("build.model"),
            "build.plan", Set.of("build.model", "build.parts", "lang"),
            "build.compile.gen", Set.of("build.model", "build.parts"),
            "build.compile", Set.of("build.model", "build.parts", "build.plan", "build.compile.gen"),
            "build.script", Set.of("build.model", "build.parts", "lang", "lang.ast"),
            "lang", Set.of("lang.ast", "build.model", "build.parts"),
            "lang.ast", Set.of());

    private static List<Path> javaFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static boolean isPurePackage(Path file) {
        String rel = ROOT.relativize(file.getParent()).toString().replace('\\', '.').replace('/', '.');
        String top = rel.contains(".") ? rel.substring(0, rel.indexOf('.')) : rel;
        return PURE_PREFIXES.contains(top);
    }

    private static String packageOf(Path file) {
        return ROOT.relativize(file.getParent()).toString().replace('\\', '.').replace('/', '.');
    }

    /** External-dependency violations of one file (import lines or qualified uses outside comments). */
    static List<String> violations(Path file, String text) {
        List<String> out = new ArrayList<>();
        int n = 0;
        for (String line : text.split("\n", -1)) {
            n++;
            String trimmed = line.trim();
            if (FORBIDDEN_IMPORT.matcher(line).find() || (!trimmed.startsWith("*") && !trimmed.startsWith("//")
                    && FORBIDDEN_QUALIFIED.matcher(line).find())) {
                out.add(file + ":" + n + ": " + trimmed);
            }
        }
        return out;
    }

    /**
     * The package of an imported internal name: the longest leading run of lowercase segments
     * ({@code build.model.ParamValue.IntV} -> {@code build.model}). A name that is nothing but a
     * package has no internal dots left after the prefix, which is impossible for a class import.
     */
    static String packageOfImport(String imported) {
        int end = imported.length();
        for (int dot = imported.indexOf('.'); dot >= 0; dot = imported.indexOf('.', dot + 1)) {
            int next = imported.indexOf('.', dot + 1);
            String segment = imported.substring(dot + 1, next < 0 ? end : next);
            if (segment.isEmpty() || !segment.equals(segment.toLowerCase(java.util.Locale.ROOT))
                    || segment.matches(".*[0-9].*")) {
                return imported.substring(0, dot);
            }
        }
        return imported;
    }

    /** Internal package names this file imports (e.g. {@code build.model} from {@code import ...build.model.Box}). */
    static Set<String> internalImports(String text) {
        Set<String> out = new HashSet<>();
        for (String line : text.split("\n", -1)) {
            var m = INTERNAL_IMPORT.matcher(line);
            if (m.find()) {
                out.add(packageOfImport(m.group(2)));
            }
        }
        return out;
    }

    /** Depth-first cycle check over {@link #ALLOWED}; returns the cycle as a list, or an empty list. */
    static List<String> findCycle() {
        Set<String> done = new HashSet<>();
        Set<String> onStack = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        for (String start : ALLOWED.keySet()) {
            if (done.contains(start)) {
                continue;
            }
            stack.push(start);
            onStack.add(start);
            while (!stack.isEmpty()) {
                String cur = stack.peek();
                String next = null;
                for (String dep : new TreeSet<>(ALLOWED.getOrDefault(cur, Set.of()))) {
                    if (!done.contains(dep)) {
                        next = dep;
                        break;
                    }
                }
                if (next == null) {
                    done.add(stack.pop());
                    onStack.remove(cur);
                } else if (onStack.contains(next)) {
                    List<String> cycle = new ArrayList<>();
                    boolean in = false;
                    for (String s : stack) {
                        if (s.equals(next)) {
                            in = true;
                        }
                        if (in) {
                            cycle.add(s);
                        }
                    }
                    return cycle;
                } else {
                    stack.push(next);
                    onStack.add(next);
                }
            }
        }
        return List.of();
    }

    @Test
    void theScannerFindsTheSources() throws IOException {
        assertTrue(javaFiles(ROOT.resolve("build")).size() > 60,
                "the build package should hold many classes");
        assertTrue(javaFiles(ROOT.resolve("lang")).size() > 20,
                "the lang package should hold the language and the plan bridge");
    }

    @Test
    void theBuildAndLangPackagesDoNotImportMinecraftOrNeoForgeOrCreate() throws IOException {
        List<String> all = new ArrayList<>();
        for (Path p : javaFiles(ROOT)) {
            if (isPurePackage(p)) {
                all.addAll(violations(p, Files.readString(p, StandardCharsets.UTF_8)));
            }
        }
        assertEquals(List.of(), all);
    }

    @Test
    void thePurePackagesKeepTheMeasuredLayering() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Path p : javaFiles(ROOT)) {
            if (!isPurePackage(p)) {
                continue;
            }
            String pkg = packageOf(p);
            Set<String> allowed = ALLOWED.get(pkg);
            if (allowed == null) {
                bad.add(pkg + " is a new package under a pure prefix; pin its allowed imports in this test");
                continue;
            }
            for (String imported : internalImports(Files.readString(p, StandardCharsets.UTF_8))) {
                if (!imported.equals(pkg) && !imported.startsWith(pkg + ".") && !allowed.contains(imported)) {
                    bad.add(p + " imports " + imported + " (not in " + pkg + "'s allowed set " + allowed + ")");
                }
            }
        }
        assertEquals(List.of(), bad);
    }

    @Test
    void theDeclaredLayeringHasNoCycle() {
        assertEquals(List.of(), findCycle());
    }

    @Test
    void theScannerCatchesARealViolation() {
        assertEquals(1, violations(Path.of("X.java"), "import net.minecraft.core.BlockPos;\n").size());
        assertEquals(1, violations(Path.of("X.java"), "import static net.neoforged.fml.Foo.bar;\n").size());
        assertEquals(1, violations(Path.of("X.java"), "var p = new net.minecraft.core.BlockPos(1, 2, 3);\n").size());
        assertEquals(0, violations(Path.of("X.java"),
                "// mentions net.minecraft in a comment\n import java.util.List;\n").size());
    }

    @Test
    void theConstructionCoreIsPureAndDoesNotReachIntoAdapters() throws IOException {
        Path core = ROOT.resolve("construction/core");
        assertTrue(Files.isDirectory(core), "construction.core must exist");
        List<String> all = new ArrayList<>();
        Pattern adapter = Pattern.compile("^\\s*import\\s+(static\\s+)?io\\.github\\.khayashi4337\\.micradrone\\."
                + "(construction\\.(?!core\\.)|drone\\.|client\\.)");
        for (Path p : javaFiles(core)) {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            all.addAll(violations(p, text));
            int n = 0;
            for (String line : text.split("\n", -1)) {
                n++;
                if (adapter.matcher(line).find()) {
                    all.add(p + ":" + n + ": " + line.trim());
                }
            }
        }
        assertEquals(List.of(), all);
    }

    @Test
    void theBuildPackagesDoNotDependOnConstruction() throws IOException {
        List<String> all = new ArrayList<>();
        for (Path p : javaFiles(ROOT.resolve("build"))) {
            if (Files.readString(p, StandardCharsets.UTF_8).contains("import io.github.khayashi4337.micradrone.construction.")) {
                all.add(p.toString());
            }
        }
        assertEquals(List.of(), all, "build.* is the lower layer; construction.core builds on it, never the reverse");
    }
}
