package io.github.khayashi4337.micradrone.chat;

import java.util.List;
import java.util.Optional;

/**
 * Assembles the text sent to claude -p for one chat turn: the player's question plus whatever
 * context the IDE already knows (current script, a command-reference excerpt, the last runtime
 * error, and any pending world-region reference) - Minecraft-free so it stays unit-testable
 * without a real IdeScreen.
 */
public final class ChatContextBuilder {
    private ChatContextBuilder() {
    }

    /**
     * Where this controller's plot sits in the world, so the AI can translate a world-coordinate
     * region (from RegionPointerItem, or from its own get_block_snapshot calls) into the grid moves
     * the script language actually speaks. Mirrors PlotGeometry#groundOffset and LiveDroneApi#move:
     * grid cell (gx, gy) is the ground block at
     * {@code (controllerX + dirX*(1+gx), controllerY + groundYOffset, controllerZ + dirZ*(1+gy))},
     * and move("east"/"west"/"south"/"north") steps gx+1 / gx-1 / gy+1 / gy-1 regardless of
     * dirX/dirZ - which is exactly the non-obvious part a model would otherwise guess wrong.
     */
    public record PlotInfo(int controllerX, int controllerY, int controllerZ,
                           int worldSize, int dirX, int dirZ, int groundYOffset) {
    }

    /**
     * @param scriptText                 the editor's current (possibly unsaved) contents
     * @param commandReferenceExcerpt    CommandsHelpDoc text to ground the reply in real commands
     * @param logLines                   the controller's log buffer; the last {@code "error: "}-prefixed
     *                                    line, if any, becomes the "last error" context
     * @param pendingRegionReferenceText RegionSelectionState#consumeAsText's result, if any
     * @param priorSummary               ChatCompactor#applySummary's result, if this session was
     *                                    ever compacted - carried forward since compacting starts a
     *                                    brand new CLI session that has no memory of its own
     * @param plot                       the plot's world placement (see {@link PlotInfo}); empty
     *                                    only when the screen has no level to read it from
     */
    public record ChatContext(
            String scriptText,
            String commandReferenceExcerpt,
            List<String> logLines,
            Optional<String> pendingRegionReferenceText,
            Optional<String> priorSummary,
            Optional<PlotInfo> plot) {
    }

    public static String build(String userQuestion, ChatContext context) {
        StringBuilder sb = new StringBuilder();
        context.priorSummary().ifPresent(summary ->
                sb.append("これまでの会話の要約:\n").append(summary).append("\n\n"));

        sb.append("現在のスクリプト:\n```\n");
        sb.append(context.scriptText().isBlank() ? "(空)" : context.scriptText());
        sb.append("\n```\n\n");

        sb.append("コマンドリファレンス抜粋:\n");
        sb.append(context.commandReferenceExcerpt());
        sb.append("\n\n");

        context.plot().ifPresent(plot -> sb.append(describePlot(plot)).append("\n\n"));

        Optional<String> lastError = lastError(context.logLines());
        if (lastError.isPresent()) {
            sb.append("直前の実行エラー: ").append(lastError.get()).append("\n\n");
        }

        context.pendingRegionReferenceText().ifPresent(region ->
                sb.append("参照範囲(ワールド座標): ").append(region).append("\n\n"));

        sb.append("質問:\n").append(userQuestion);
        return sb.toString();
    }

    /** The world-to-grid mapping spelled out with this plot's actual numbers, so nothing is left to infer. */
    static String describePlot(PlotInfo plot) {
        int last = plot.worldSize() - 1;
        return "プロット情報(ワールド座標とドローンのグリッド座標の対応):\n"
                + "- コントローラのワールド座標: (" + plot.controllerX() + "," + plot.controllerY() + "," + plot.controllerZ() + ")\n"
                + "- グリッドは " + plot.worldSize() + "x" + plot.worldSize() + "、グリッド座標 (gx, gy) はそれぞれ 0.." + last
                + "。get_pos_x()/get_pos_y() が今のドローンの (gx, gy)\n"
                + "- グリッド (gx, gy) の地面ブロックのワールド座標: x = " + plot.controllerX() + " + (" + plot.dirX() + ")*(1+gx), y = "
                + (plot.controllerY() + plot.groundYOffset()) + ", z = " + plot.controllerZ() + " + (" + plot.dirZ() + ")*(1+gy)。作物はその1つ上(y+1)\n"
                + "- move(\"east\") は gx+1、move(\"west\") は gx-1、move(\"south\") は gy+1、move(\"north\") は gy-1"
                + "(ワールドの方角ではなく、上の式の gx/gy に対する増減)";
    }

    /** The most recent {@code "error: "}-prefixed log line, with that prefix stripped. */
    static Optional<String> lastError(List<String> logLines) {
        for (int i = logLines.size() - 1; i >= 0; i--) {
            String line = logLines.get(i);
            if (line.startsWith("error: ")) {
                return Optional.of(line.substring("error: ".length()));
            }
        }
        return Optional.empty();
    }
}
