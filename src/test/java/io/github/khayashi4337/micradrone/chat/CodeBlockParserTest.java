package io.github.khayashi4337.micradrone.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.khayashi4337.micradrone.chat.CodeBlockParser.CodeBlock;

class CodeBlockParserTest {

    @Test
    void noFencesYieldsNoBlocks() {
        assertEquals(List.of(), CodeBlockParser.parse("just a plain chat reply, no code here."));
    }

    @Test
    void extractsASingleFencedBlockWithLanguageHint() {
        String reply = """
                Try this:
                ```python
                move("north")
                harvest()
                ```
                That should work.""";

        List<CodeBlock> blocks = CodeBlockParser.parse(reply);

        assertEquals(1, blocks.size());
        assertEquals("python", blocks.get(0).language());
        assertEquals("move(\"north\")\nharvest()", blocks.get(0).code());
    }

    @Test
    void extractsMultipleBlocksInAppearanceOrder() {
        String reply = """
                First:
                ```
                a = 1
                ```
                Second:
                ```
                b = 2
                ```""";

        List<CodeBlock> blocks = CodeBlockParser.parse(reply);

        assertEquals(2, blocks.size());
        assertEquals("a = 1", blocks.get(0).code());
        assertEquals("b = 2", blocks.get(1).code());
    }

    @Test
    void missingLanguageHintYieldsEmptyLanguage() {
        String reply = "```\nx = 1\n```";
        assertEquals("", CodeBlockParser.parse(reply).get(0).language());
    }

    @Test
    void anUnclosedFenceAtTheEndIsDropped() {
        String reply = """
                Here's a start:
                ```python
                move("north")""";

        assertTrue(CodeBlockParser.parse(reply).isEmpty());
    }

    @Test
    void aClosedBlockBeforeAnUnclosedTrailingFenceIsStillReturned() {
        String reply = """
                ```
                a = 1
                ```
                ```python
                unfinished...""";

        List<CodeBlock> blocks = CodeBlockParser.parse(reply);

        assertEquals(1, blocks.size());
        assertEquals("a = 1", blocks.get(0).code());
    }

    @Test
    void anEmptyFencedBlockYieldsEmptyCode() {
        String reply = "```\n```";
        assertEquals("", CodeBlockParser.parse(reply).get(0).code());
    }
}
