package io.github.khayashi4337.micradrone.build.ai;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * L1: the child's one-liner language follows the game's language code - the 16 supported codes
 * resolve by full code, an unlisted region variant resolves by its base language, a code no table
 * knows keeps its code instead of silently becoming English, and malformed input becomes en_us.
 */
class PromptLanguageTest {
    @Test
    void theSixteenSupportedCodesResolveToTheirEnglishNames() {
        Map<String, String> expected = Map.ofEntries(
                entry("ja_jp", "Japanese"), entry("en_us", "English"),
                entry("zh_cn", "Simplified Chinese"), entry("zh_tw", "Traditional Chinese"),
                entry("ko_kr", "Korean"), entry("th_th", "Thai"),
                entry("es_es", "Spanish"), entry("fr_fr", "French"),
                entry("de_de", "German"), entry("it_it", "Italian"),
                entry("pt_br", "Brazilian Portuguese"), entry("ru_ru", "Russian"),
                entry("hi_in", "Hindi"), entry("tr_tr", "Turkish"),
                entry("pl_pl", "Polish"), entry("id_id", "Indonesian"));
        assertEquals(16, PromptLanguage.SUPPORTED_CODES.size(),
                "the owner-fixed set is exactly these 16 languages");
        for (Map.Entry<String, String> e : expected.entrySet()) {
            PromptLanguage lang = PromptLanguage.of(e.getKey());
            assertEquals(e.getValue(), lang.englishName(), e.getKey());
            assertEquals(e.getKey(), lang.code(), e.getKey());
        }
    }

    @Test
    void theSixteenSupportedCodesCarryTheirNativeNames() {
        Map<String, String> expected = Map.ofEntries(
                entry("ja_jp", "日本語"), entry("en_us", "English"),
                entry("zh_cn", "简体中文"), entry("zh_tw", "繁體中文"),
                entry("ko_kr", "한국어"), entry("th_th", "ไทย"),
                entry("es_es", "español"), entry("fr_fr", "français"),
                entry("de_de", "Deutsch"), entry("it_it", "italiano"),
                entry("pt_br", "português do Brasil"), entry("ru_ru", "русский"),
                entry("hi_in", "हिन्दी"), entry("tr_tr", "Türkçe"),
                entry("pl_pl", "polski"), entry("id_id", "Bahasa Indonesia"));
        for (Map.Entry<String, String> e : expected.entrySet()) {
            assertEquals(e.getValue(), PromptLanguage.of(e.getKey()).nativeName(), e.getKey());
        }
    }

    @Test
    void mixedCaseAndDashSeparatedCodesAreNormalized() {
        PromptLanguage pt = PromptLanguage.of("PT-br");
        assertEquals("pt_br", pt.code());
        assertEquals("Brazilian Portuguese", pt.englishName());
        PromptLanguage tw = PromptLanguage.of("zh-TW");
        assertEquals("zh_tw", tw.code());
        assertEquals("Traditional Chinese", tw.englishName());
        assertEquals("繁體中文", tw.nativeName());
    }

    @Test
    void aCodeOutsideTheTableResolvesByItsBaseLanguage() {
        assertEquals("Spanish", PromptLanguage.of("es_mx").englishName());
        assertEquals("español", PromptLanguage.of("es_mx").nativeName());
        assertEquals("Spanish", PromptLanguage.of("es_ar").englishName());
        assertEquals("English", PromptLanguage.of("en_gb").englishName());
        assertEquals("English", PromptLanguage.of("en_au").englishName());
        assertEquals("English", PromptLanguage.of("en_ca").englishName());
        assertEquals("French", PromptLanguage.of("fr_ca").englishName());
        assertEquals("Portuguese", PromptLanguage.of("pt_pt").englishName());
        assertEquals("português", PromptLanguage.of("pt_pt").nativeName());
        assertEquals("Traditional Chinese", PromptLanguage.of("zh_hk").englishName());
        assertEquals("Traditional Chinese", PromptLanguage.of("zh_mo").englishName());
        // a bare zh and a region no table names are Simplified (the majority script), not Traditional
        assertEquals("Simplified Chinese", PromptLanguage.of("zh").englishName());
        assertEquals("Simplified Chinese", PromptLanguage.of("zh_sg").englishName());
        assertEquals("German", PromptLanguage.of("de_at").englishName());
        assertEquals("German", PromptLanguage.of("de_ch").englishName());
    }

    @Test
    void aCodeNoTableKnowsKeepsTheCodeAndHasNoNativeName() {
        PromptLanguage fi = PromptLanguage.of("fi_fi");
        assertEquals("fi_fi", fi.code());
        assertEquals("fi_fi", fi.englishName(), "the code itself stands in for the name");
        assertNull(fi.nativeName());
        assertFalse(fi.isJapanese());
    }

    @Test
    void malformedCodesFallBackToEnUs() {
        String[] bad = {null, "", "   ", "ja_jp; rm -rf", "ja@jp", "a".repeat(17)};
        for (String code : bad) {
            PromptLanguage lang = PromptLanguage.of(code);
            assertEquals("en_us", lang.code(), String.valueOf(code));
            assertEquals("English", lang.englishName());
            assertFalse(lang.isJapanese());
        }
        // a code exactly at the length limit keeps its own (unknown) code, not the fallback
        assertEquals("a".repeat(16), PromptLanguage.of("a".repeat(16)).code());
    }

    @Test
    void isJapaneseFollowsTheBaseLanguage() {
        assertTrue(PromptLanguage.of("ja_jp").isJapanese());
        assertTrue(PromptLanguage.of("ja_JP").isJapanese());
        assertTrue(PromptLanguage.of("ja").isJapanese());
        assertFalse(PromptLanguage.of("en_us").isJapanese());
        assertFalse(PromptLanguage.of("fi_fi").isJapanese());
    }
}
