package io.github.khayashi4337.micradrone.build.ai;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Writes one sample build prompt per supported language so the real AI's answer can be checked by
 * hand. Runs only when the environment variable MICRADRONE_DUMP_PROMPTS names a folder; a normal
 * test run skips it (assumption fails) and stays green without writing anything.
 */
class PromptDumpTest {
    private static final String ENV_DIR = "MICRADRONE_DUMP_PROMPTS";
    // The request stays this one Japanese sentence for every language: L1 changes only the
    // language the child-facing one-liner is written in, not what the child asked.
    private static final String REQUEST = "屋根が赤い小屋を建てて";
    private static final String SAMPLE = "{\n  \"patchId\": \"s-1\",\n  \"ops\": []\n}";
    private static final String CATALOG = "- micra:structure: width 3..64, depth 3..64, floors 1..8";
    private static final String ALLOWED = "minecraft:oak_planks, minecraft:stone_bricks";

    @Test
    void dumpsOnePromptPerSupportedLanguage() throws IOException {
        String dir = System.getenv(ENV_DIR);
        Assumptions.assumeTrue(dir != null && !dir.isBlank(),
                "set " + ENV_DIR + " to a folder to dump the prompts");
        Path outDir = Path.of(dir);
        Files.createDirectories(outDir);
        for (String code : PromptLanguage.SUPPORTED_CODES) {
            String prompt = BuildPromptBuilder.build(REQUEST, SAMPLE, CATALOG, ALLOWED,
                    PromptLanguage.of(code));
            Path file = outDir.resolve("prompt-" + code + ".txt");
            Files.writeString(file, prompt, StandardCharsets.UTF_8);
            assertTrue(Files.exists(file), "dump written: " + file);
        }
    }
}
