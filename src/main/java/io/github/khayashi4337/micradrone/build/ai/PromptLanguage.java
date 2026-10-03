package io.github.khayashi4337.micradrone.build.ai;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The language the AI writes the child-facing one-liner in (L1), decided from the game's Minecraft
 * language code: lowercased, '-' becomes '_'. A full code hits the 16-language table; an unlisted
 * region variant resolves by its base language (es_mx -> Spanish); a code no table knows keeps its
 * code so the prompt can name it to the AI - never a silent English fallback, the model speaks
 * languages the table does not list. Malformed input (empty, overlong, or carrying characters that
 * are not letters, digits or '_') becomes en_us so raw user input never reaches a prompt.
 */
public record PromptLanguage(String code, String englishName, String nativeName) {
    /** Longest accepted language code; longer input is treated as malformed. */
    private static final int MAX_CODE_LENGTH = 16;
    private static final Pattern CODE_SHAPE = Pattern.compile("[a-z0-9_]+");
    /** Malformed input lands here (never null inside a prompt). */
    private static final String FALLBACK_CODE = "en_us";
    /** The language the pre-L1 fixed prompts were written in; the default constructor argument. */
    public static final String JAPANESE_CODE = "ja_jp";

    private record Names(String english, String nativeName) {
    }

    // The 16 languages the owner picked (2026-10-02): full code -> English name + native name.
    private static final Map<String, Names> BY_FULL_CODE = Map.ofEntries(
            Map.entry("ja_jp", new Names("Japanese", "日本語")),
            Map.entry("en_us", new Names("English", "English")),
            Map.entry("zh_cn", new Names("Simplified Chinese", "简体中文")),
            Map.entry("zh_tw", new Names("Traditional Chinese", "繁體中文")),
            Map.entry("ko_kr", new Names("Korean", "한국어")),
            Map.entry("th_th", new Names("Thai", "ไทย")),
            Map.entry("es_es", new Names("Spanish", "español")),
            Map.entry("fr_fr", new Names("French", "français")),
            Map.entry("de_de", new Names("German", "Deutsch")),
            Map.entry("it_it", new Names("Italian", "italiano")),
            Map.entry("pt_br", new Names("Brazilian Portuguese", "português do Brasil")),
            Map.entry("ru_ru", new Names("Russian", "русский")),
            Map.entry("hi_in", new Names("Hindi", "हिन्दी")),
            Map.entry("tr_tr", new Names("Turkish", "Türkçe")),
            Map.entry("pl_pl", new Names("Polish", "polski")),
            Map.entry("id_id", new Names("Indonesian", "Bahasa Indonesia")));

    /**
     * The base-language fallback for codes outside {@link #BY_FULL_CODE}. Note pt resolves to
     * generic Portuguese (pt_pt) here while the full table keeps pt_br = Brazilian Portuguese,
     * and a bare zh (or a region such as zh_sg that no table names) resolves to Simplified Chinese, the
     * majority script; the Traditional regions are named in {@link #TRADITIONAL_CHINESE_REGIONS}.
     */
    private static final Map<String, Names> BY_BASE_LANGUAGE = Map.ofEntries(
            Map.entry("ja", new Names("Japanese", "日本語")),
            Map.entry("en", new Names("English", "English")),
            Map.entry("zh", new Names("Simplified Chinese", "简体中文")),
            Map.entry("ko", new Names("Korean", "한국어")),
            Map.entry("th", new Names("Thai", "ไทย")),
            Map.entry("es", new Names("Spanish", "español")),
            Map.entry("fr", new Names("French", "français")),
            Map.entry("de", new Names("German", "Deutsch")),
            Map.entry("it", new Names("Italian", "italiano")),
            Map.entry("pt", new Names("Portuguese", "português")),
            Map.entry("ru", new Names("Russian", "русский")),
            Map.entry("hi", new Names("Hindi", "हिन्दी")),
            Map.entry("tr", new Names("Turkish", "Türkçe")),
            Map.entry("pl", new Names("Polish", "polski")),
            Map.entry("id", new Names("Indonesian", "Bahasa Indonesia")));

    /** Chinese regions that write Traditional characters but are not among the 16 codes of {@link #BY_FULL_CODE}. */
    private static final Set<String> TRADITIONAL_CHINESE_REGIONS = Set.of("zh_hk", "zh_mo");

    /** The 16 codes the table knows; tests and the prompt dump iterate this set. */
    public static final Set<String> SUPPORTED_CODES = BY_FULL_CODE.keySet();

    /**
     * Resolves {@code minecraftCode} (e.g. {@code ja_jp}, {@code zh-TW}): full-code lookup first,
     * then the base language, then the bare code. Malformed input becomes {@code en_us}.
     */
    public static PromptLanguage of(String minecraftCode) {
        String code = normalize(minecraftCode);
        Names names = BY_FULL_CODE.get(code);
        if (names == null && TRADITIONAL_CHINESE_REGIONS.contains(code)) {
            names = BY_FULL_CODE.get("zh_tw");
        }
        if (names == null) {
            names = BY_BASE_LANGUAGE.get(baseLanguage(code));
        }
        // an unknown code keeps itself: englishName carries the code, nativeName stays null
        return names == null ? new PromptLanguage(code, code, null)
                : new PromptLanguage(code, names.english(), names.nativeName());
    }

    /** True when the resolved language's base is ja - the prompt keeps its pre-L1 Japanese text. */
    public boolean isJapanese() {
        return "ja".equals(baseLanguage(code));
    }

    /**
     * The language as the prompt names it: {@code English(English)} for a table entry, or
     * {@code the language of the Minecraft language code `xx_yy`} for a code outside every table
     * - the AI is asked by code so an unlisted language still comes back in that language.
     */
    String displayName() {
        if (nativeName == null) {
            return "the language of the Minecraft language code `" + code + "`";
        }
        return englishName + "(" + nativeName + ")";
    }

    private static String normalize(String minecraftCode) {
        if (minecraftCode == null) {
            return FALLBACK_CODE;
        }
        String code = minecraftCode.toLowerCase(Locale.ROOT).replace('-', '_');
        if (code.length() > MAX_CODE_LENGTH || !CODE_SHAPE.matcher(code).matches()) {
            return FALLBACK_CODE;
        }
        return code;
    }

    private static String baseLanguage(String code) {
        int underscore = code.indexOf('_');
        return underscore < 0 ? code : code.substring(0, underscore);
    }
}
