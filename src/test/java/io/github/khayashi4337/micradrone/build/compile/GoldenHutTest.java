package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.PlanJson;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.plan.PatchResult;
import io.github.khayashi4337.micradrone.build.plan.PlanPatcher;
import io.github.khayashi4337.micradrone.build.plan.TemplateBundle;
import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GoldenHutTest {
    private static final String GOLDEN_DIR = "/build/golden/";
    private static final String HUT_PATCH = "hut.patch.json";
    static final String HUT_MANIFEST = "hut.manifest.txt";
    /** The manifest line that carries the hash, both in the golden file and in a fresh manifest text. */
    static final String HASH_PREFIX = "hash ";
    /** Where the candidate manifest is written for review when the golden file is still missing. */
    private static final Path CANDIDATE = Path.of("build/golden-candidate/" + HUT_MANIFEST);

    // Hand-derived from the design rules (7x7 footprint, 1 floor, floor height 4):
    // foundation 49 + floor 49 + walls 72 - door hole 2 - window holes 4 + door blocks 2 + glass 4
    // + roof 67 (42 stairs + 18 gable fill + 7 ridge oak_planks blocks) + lantern 1 = 238.
    private static final int EXPECTED_PLACEMENTS = 238;
    private static final Map<String, Integer> EXPECTED_BOM = Map.of(
            "minecraft:cobblestone", 49,
            "minecraft:glass_pane", 4,
            "minecraft:lantern", 1,
            "minecraft:oak_door", 1,
            "minecraft:oak_planks", 56,
            "minecraft:oak_stairs", 42,
            "minecraft:stone_bricks", 84);
    private static final List<BuildPhase> EXPECTED_PHASES = List.of(
            BuildPhase.STRUCTURE, BuildPhase.ENVELOPE, BuildPhase.DECORATION);

    static String resource(String name) throws IOException {
        try (InputStream in = GoldenHutTest.class.getResourceAsStream(GOLDEN_DIR + name)) {
            assertNotNull(in, "missing golden resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static SemanticPlan hut() throws IOException {
        PatchResult r = new PlanPatcher(CompileFixtures.REGISTRY, TemplateBundle.EMPTY)
                .apply(SemanticPlan.empty("hut"), PlanJson.patchFromTree(MiniJson.parse(resource(HUT_PATCH))));
        assertTrue(r.ok(), r.issues().toString());
        return r.plan();
    }

    /** One line per placement: index x y z block verify phase (properties are part of the block text). */
    static List<String> lines(PlacementManifest m) {
        List<String> out = new ArrayList<>();
        for (Placement p : m.placements()) {
            out.add(p.index() + " " + p.pos().x() + " " + p.pos().y() + " " + p.pos().z() + " " + p.block() + " "
                    + p.verify() + " " + p.phase() + (p.blockEntityConfig().isEmpty() ? "" : " " + p.blockEntityConfig()));
        }
        return out;
    }

    @Test
    void theHandWrittenPatchBuildsTheHutWithTheDerivedNumbers() throws IOException {
        CompileResult r = CompileFixtures.compile(hut());
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        PlacementManifest m = r.manifest();
        assertEquals(EXPECTED_PLACEMENTS, m.placements().size());
        assertEquals(EXPECTED_BOM, m.bom());
        assertEquals(EXPECTED_PHASES, m.phases().stream().map(PhaseRange::phase).toList());
    }

    @Test
    void theManifestMatchesTheGoldenFile() throws IOException {
        PlacementManifest m = CompileFixtures.compile(hut()).manifest();
        List<String> actual = new ArrayList<>();
        actual.add(HASH_PREFIX + m.hash());
        actual.addAll(lines(m));
        try (InputStream in = GoldenHutTest.class.getResourceAsStream(GOLDEN_DIR + HUT_MANIFEST)) {
            if (in == null) {
                Files.createDirectories(CANDIDATE.getParent());
                Files.writeString(CANDIDATE, String.join("\n", actual) + "\n", StandardCharsets.UTF_8);
                org.junit.jupiter.api.Assertions.fail("there is no golden file yet; a candidate was written to "
                        + CANDIDATE.toAbsolutePath()
                        + " - check it against the derived numbers, then copy it to src/test/resources" + GOLDEN_DIR
                        + HUT_MANIFEST);
            }
            List<String> golden = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n").stripTrailing().lines().toList();
            assertEquals(golden, actual, "the manifest drifted from the golden file. To regenerate it: delete "
                    + "src/test/resources" + GOLDEN_DIR + HUT_MANIFEST + " and run this test, which writes a candidate "
                    + "to " + CANDIDATE.toAbsolutePath() + " - CHECK the candidate against the derived numbers before "
                    + "copying it to the golden directory (the test never overwrites an existing golden)");
        }
    }
}
