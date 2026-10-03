package io.github.khayashi4337.micradrone.build.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void thePromptStatesTheWakachiRuleForTheOneLiner() {
        String prompt = BuildPromptBuilder.build(REQUEST, SAMPLE, CATALOG, ALLOWED);
        assertTrue(prompt.contains(BuildPromptBuilder.WAKACHI_RULE),
                "the one-liner rule must be the shared wakachi-gaki sentence");
        assertTrue(prompt.contains("ことばの あいだに スペースを いれて"),
                "the wakachi-gaki instruction itself must be stated, not just the constant name");
    }

    @Test
    void thePromptPinsNumericArgumentsToTheCatalogRange() {
        String prompt = BuildPromptBuilder.build(REQUEST, SAMPLE, CATALOG, ALLOWED);
        assertTrue(prompt.contains("数値の引数は、上の一覧の最小と最大の範囲を必ず守る。迷ったら見本の値を使う"),
                "numeric arguments must stay inside the listed min/max");
    }

    @Test
    void theChildRequestMarkerIsLastAndOnlyTheRequestFollowsIt() {
        String prompt = BuildPromptBuilder.build(REQUEST, SAMPLE, CATALOG, ALLOWED);
        assertTrue(prompt.endsWith("子供の依頼:\n" + REQUEST + "\n"),
                "the prompt must end with the marker line and the request verbatim");
        assertEquals(prompt.indexOf("子供の依頼:"), prompt.lastIndexOf("子供の依頼:"),
                "the marker must occur exactly once, so the last one is the real one");
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

    // ---- L1: the child-facing one-liner follows the game's language ----------------------------------

    @Test
    void theFiveArgumentOverloadWithJapaneseEqualsTheFourArgumentForm() {
        String four = BuildPromptBuilder.build(REQUEST, SAMPLE, CATALOG, ALLOWED);
        String five = BuildPromptBuilder.build(REQUEST, SAMPLE, CATALOG, ALLOWED,
                PromptLanguage.of("ja_jp"));
        assertEquals(four, five, "ja_jp is the fixed-Japanese behaviour the 4-arg form always had");
    }

    @Test
    void aNonJapaneseLanguageReplacesTheHiraganaAndWakachiRules() {
        String prompt = BuildPromptBuilder.build(REQUEST, SAMPLE, CATALOG, ALLOWED,
                PromptLanguage.of("en_us"));
        assertFalse(prompt.contains(BuildPromptBuilder.WAKACHI_RULE),
                "word-spacing is a Japanese-learner rule; it must not appear for English");
        assertFalse(prompt.contains("ひらがなの短い一言"),
                "the hiragana one-liner line is replaced, not kept beside the new one");
        assertTrue(prompt.contains("English"));
        // the language change touches ONLY the one-liner rule: sample, catalog and blocks stay
        assertTrue(prompt.contains(SAMPLE));
        assertTrue(prompt.contains(CATALOG));
        assertTrue(prompt.contains(ALLOWED));
    }

    @Test
    void theLanguageLineNamesBothTheEnglishAndNativeNames() {
        String prompt = BuildPromptBuilder.build(REQUEST, SAMPLE, CATALOG, ALLOWED,
                PromptLanguage.of("th_th"));
        assertTrue(prompt.contains("Thai(ไทย)"),
                "the one-liner rule names the language as EnglishName(nativeName)");
    }

    @Test
    void anUnknownCodeIsNamedByItsNormalizedCodeNotTheRawInput() {
        String prompt = BuildPromptBuilder.build(REQUEST, SAMPLE, CATALOG, ALLOWED,
                PromptLanguage.of("FI-fi"));
        assertTrue(prompt.contains("the language of the Minecraft language code `fi_fi`"),
                "an unlisted language is asked for by its normalized code");
        assertFalse(prompt.contains("FI-fi"),
                "the raw caller string never enters the prompt unnormalized");
    }

    @Test
    void everySupportedLanguageKeepsTheJsonAndMaterialsRules() {
        for (String code : PromptLanguage.SUPPORTED_CODES) {
            String prompt = BuildPromptBuilder.build(REQUEST, SAMPLE, CATALOG, ALLOWED,
                    PromptLanguage.of(code));
            assertTrue(prompt.contains("```json"), code);
            assertTrue(prompt.contains("set_site"), code);
            assertTrue(prompt.contains("materials"), code);
        }
    }
}
