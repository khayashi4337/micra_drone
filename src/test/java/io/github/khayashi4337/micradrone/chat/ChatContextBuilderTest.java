package io.github.khayashi4337.micradrone.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.khayashi4337.micradrone.chat.ChatContextBuilder.ChatContext;

class ChatContextBuilderTest {

    private static ChatContext contextOf(String script, List<String> logLines, Optional<String> region) {
        return new ChatContext(script, "move(dir) / harvest() / ...", logLines, region, Optional.empty());
    }

    @Test
    void includesTheScriptAndReferenceAndQuestion() {
        ChatContext context = contextOf("move(\"north\")", List.of(), Optional.empty());

        String prompt = ChatContextBuilder.build("なぜこれは動かないの?", context);

        assertTrue(prompt.contains("move(\"north\")"));
        assertTrue(prompt.contains("move(dir) / harvest() / ..."));
        assertTrue(prompt.contains("なぜこれは動かないの?"));
    }

    @Test
    void emptyScriptIsShownAsEmptyRatherThanBlank() {
        ChatContext context = contextOf("", List.of(), Optional.empty());
        assertTrue(ChatContextBuilder.build("質問", context).contains("(空)"));
    }

    @Test
    void omitsTheLastErrorSectionWhenThereIsNoErrorLine() {
        ChatContext context = contextOf("x = 1", List.of("何か普通のログ", "print output"), Optional.empty());
        assertFalse(ChatContextBuilder.build("質問", context).contains("直前の実行エラー"));
    }

    @Test
    void extractsTheMostRecentErrorPrefixedLogLine() {
        ChatContext context = contextOf("x = 1",
                List.of("error: line 2: unexpected token", "some later non-error line", "error: line 5: division by zero"),
                Optional.empty());

        String prompt = ChatContextBuilder.build("質問", context);

        assertTrue(prompt.contains("直前の実行エラー: line 5: division by zero"));
        assertFalse(prompt.contains("line 2: unexpected token"));
    }

    @Test
    void omitsTheRegionSectionWhenNoneIsPending() {
        ChatContext context = contextOf("x = 1", List.of(), Optional.empty());
        assertFalse(ChatContextBuilder.build("質問", context).contains("参照範囲"));
    }

    @Test
    void includesThePendingRegionReferenceWhenPresent() {
        ChatContext context = contextOf("x = 1", List.of(), Optional.of("(1,64,2)~(3,64,4)"));
        assertTrue(ChatContextBuilder.build("質問", context).contains("参照範囲(ワールド座標): (1,64,2)~(3,64,4)"));
    }

    @Test
    void lastErrorHelperReturnsEmptyForNoLogLines() {
        assertEquals(Optional.empty(), ChatContextBuilder.lastError(List.of()));
    }

    @Test
    void omitsTheSummarySectionWhenSessionWasNeverCompacted() {
        ChatContext context = contextOf("x = 1", List.of(), Optional.empty());
        assertFalse(ChatContextBuilder.build("質問", context).contains("これまでの会話の要約"));
    }

    @Test
    void includesThePriorSummaryWhenTheSessionWasCompacted() {
        ChatContext context = new ChatContext("x = 1", "ref", List.of(), Optional.empty(),
                Optional.of("以前、収穫ループのバグを直した"));

        String prompt = ChatContextBuilder.build("質問", context);

        assertTrue(prompt.contains("これまでの会話の要約:\n以前、収穫ループのバグを直した"));
    }
}
