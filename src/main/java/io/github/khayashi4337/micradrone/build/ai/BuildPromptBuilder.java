package io.github.khayashi4337.micradrone.build.ai;

import java.util.Objects;

/**
 * Builds the single Japanese prompt sent to the AI when a child asks for a building (P4 task M1): the
 * child's request becomes a plan JSON that the server then verifies. Section order follows the task
 * design: role, reply rules, the sample plan with the keep-{@code set_site} note, the part catalog and
 * the allowed blocks, the no-questions default rule, and the child's request last.
 */
public final class BuildPromptBuilder {
    /**
     * Longest child request kept verbatim; longer ones are cut so a pasted wall of text cannot
     * crowd out the rules and the catalog in one prompt.
     */
    public static final int MAX_REQUEST_CHARS = 4000;

    /**
     * One-liner rule shared with {@link RepairPromptBuilder}: the hiragana opening line must put
     * a space between words (wakachi-gaki) so a child can read it. Design source: task M1b,
     * evidence run-evidence/p4/p4-mvp-007.
     */
    static final String WAKACHI_RULE =
            "ひらがなの短い一言は、ことばの あいだに スペースを いれて(分かち書き)、子供が読める ようにする。例: `やねが あかい こやを つくるよ`";

    /**
     * Numeric-argument rule pinned to the catalog ranges so the first plan already passes the
     * server's min/max checks instead of needing a repair round. Design source: task M1b.
     */
    private static final String RANGE_RULE =
            "数値の引数は、上の一覧の最小と最大の範囲を必ず守る。迷ったら見本の値を使う";

    private BuildPromptBuilder() {
    }

    public static String build(String childRequest, String sampleJson, String partsCatalog,
                               String allowedBlocks) {
        Objects.requireNonNull(childRequest, "childRequest");
        if (childRequest.isBlank()) {
            throw new IllegalArgumentException("childRequest is blank");
        }
        Objects.requireNonNull(sampleJson, "sampleJson");
        Objects.requireNonNull(partsCatalog, "partsCatalog");
        Objects.requireNonNull(allowedBlocks, "allowedBlocks");
        String request = childRequest.length() > MAX_REQUEST_CHARS
                ? childRequest.substring(0, MAX_REQUEST_CHARS) : childRequest;
        return "あなたは、子供のマインクラフトの建築を手伝う先生です。子供の依頼を、下の形のJSONの「計画」に直してください。\n\n"
                + "返答の規則:\n"
                + "- 最初に、ひらがなの短い一言(何を建てるか)を書くこと。\n"
                + "- " + WAKACHI_RULE + "\n"
                + "- そのあとに、計画のJSONを```jsonブロックでちょうど1つだけ書くこと。\n"
                + "- JSON以外の説明を長く書かないこと。\n\n"
                + "計画の形の見本:\n```json\n" + sampleJson + "\n```\n"
                + "「set_site」の操作は見本のまま変えないこと(サーバーが足元に置き直します)。\n\n"
                + "使える部品と引数の範囲:\n" + partsCatalog + "\n"
                + RANGE_RULE + "\n\n"
                + "使えるブロック:\n" + allowedBlocks + "\n\n"
                + "依頼が曖昧でも質問はしないこと。見本の大きさと素材を既定にして、子供らしい妥当な計画を作ること。\n\n"
                + "子供の依頼:\n" + request + "\n";
    }
}
