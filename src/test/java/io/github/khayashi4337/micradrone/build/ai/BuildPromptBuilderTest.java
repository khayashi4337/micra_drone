package io.github.khayashi4337.micradrone.build.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BuildPromptBuilderTest {
    private static final String SAMPLE = "{\n  \"patchId\": \"s-1\",\n  \"ops\": []\n}";
    private static final String CATALOG = "- micra:structure: width 3..64, depth 3..64, floors 1..8";
    private static final String ALLOWED = "minecraft:oak_planks, minecraft:stone_bricks";
    private static final String REQUEST = "やねが あかい こやを たてて";

    @Test
    void thePromptCarriesTheSampleTheCatalogTheBlocksAndTheRequest() {
        String prompt = BuildPromptBuilder.build(REQUEST, SAMPLE, CATALOG, ALLOWED);
        assertTrue(prompt.contains(SAMPLE), "the sample plan must be embedded verbatim");
        assertTrue(prompt.contains("```json\n" + SAMPLE + "\n```"),
                "the sample must sit inside a ```json fence");
        assertTrue(prompt.contains(CATALOG));
        assertTrue(prompt.contains(ALLOWED));
        assertTrue(prompt.contains(REQUEST));
    }

    @Test
    void thePromptStatesTheReplyRulesAndTheSetSiteNote() {
        String prompt = BuildPromptBuilder.build(REQUEST, SAMPLE, CATALOG, ALLOWED);
        assertTrue(prompt.contains("```json"), "the reply format must name the json fence");
        assertTrue(prompt.contains("ちょうど1つ"), "exactly one json block is required");
        assertTrue(prompt.contains("set_site"), "the set_site keep-as-is note is required");
        assertTrue(prompt.contains("ひらがな"), "the child-facing one-liner is required");
    }

    @Test
    void theSectionsComeInTheDesignedOrder() {
        String prompt = BuildPromptBuilder.build(REQUEST, SAMPLE, CATALOG, ALLOWED);
        int role = prompt.indexOf("手伝う先生");
        int rules = prompt.indexOf("返答の規則");
        int sample = prompt.indexOf(SAMPLE);
        int catalog = prompt.indexOf(CATALOG);
        int blocks = prompt.indexOf(ALLOWED);
        int defaults = prompt.indexOf("質問はしない");
        int request = prompt.indexOf("子供の依頼:");
        assertTrue(0 <= role && role < rules, "role first, then the reply rules");
        assertTrue(rules < sample, "rules before the sample");
        assertTrue(sample < catalog && catalog < blocks, "sample, then parts, then blocks");
        assertTrue(blocks < defaults && defaults < request, "defaults, then the child request last");
        assertTrue(prompt.indexOf(REQUEST) > request, "the request text closes the prompt");
    }

    @Test
    void aBlankRequestIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BuildPromptBuilder.build("", SAMPLE, CATALOG, ALLOWED));
        assertThrows(IllegalArgumentException.class,
                () -> BuildPromptBuilder.build("   \n\t  ", SAMPLE, CATALOG, ALLOWED));
    }

    @Test
    void aRequestLongerThanTheCapIsCutAtTheCap() {
        String request = "あ".repeat(BuildPromptBuilder.MAX_REQUEST_CHARS) + "終";
        String prompt = BuildPromptBuilder.build(request, SAMPLE, CATALOG, ALLOWED);
        assertTrue(prompt.contains("あ".repeat(BuildPromptBuilder.MAX_REQUEST_CHARS)),
                "the first MAX_REQUEST_CHARS chars are kept");
        assertFalse(prompt.contains("終"), "the char past the cap is dropped");
    }

    @Test
    void aRequestAtTheCapIsKeptWhole() {
        String request = "あ".repeat(BuildPromptBuilder.MAX_REQUEST_CHARS - 1) + "終";
        String prompt = BuildPromptBuilder.build(request, SAMPLE, CATALOG, ALLOWED);
        assertTrue(prompt.contains(request));
    }
}
