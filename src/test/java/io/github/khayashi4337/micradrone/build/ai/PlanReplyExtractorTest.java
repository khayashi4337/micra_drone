package io.github.khayashi4337.micradrone.build.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PlanReplyExtractorTest {

    @Test
    void aHiraganaLineThenAJsonBlockSplitsIntoChildSaysAndJson() {
        PlanReplyExtractor.Extracted e = PlanReplyExtractor.extract(
                "やねが あかい こやを つくるよ\n```json\n{\"ops\":[]}\n```");
        assertNull(e.error());
        assertEquals("{\"ops\":[]}", e.json());
        assertEquals("やねが あかい こやを つくるよ", e.childSays());
    }

    @Test
    void theLastJsonBlockWinsWhenThereAreSeveral() {
        PlanReplyExtractor.Extracted e = PlanReplyExtractor.extract(
                "つくるよ\n```json\n{\"ops\":[],\"v\":1}\n```\nなおしたよ\n```json\n{\"ops\":[],\"v\":2}\n```");
        assertNull(e.error());
        assertEquals("{\"ops\":[],\"v\":2}", e.json());
    }

    @Test
    void aHintlessFenceIsTakenWhenNoJsonBlockExists() {
        PlanReplyExtractor.Extracted e = PlanReplyExtractor.extract(
                "つくるよ\n```\n{\"ops\":[]}\n```");
        assertNull(e.error());
        assertEquals("{\"ops\":[]}", e.json());
    }

    @Test
    void aNonJsonFenceIsTheFallbackNotAPreferredBlock() {
        PlanReplyExtractor.Extracted e = PlanReplyExtractor.extract(
                "```\n{\"ops\":[]}\n```\n```json\n{\"ops\":[],\"v\":2}\n```");
        assertNull(e.error());
        assertEquals("{\"ops\":[],\"v\":2}", e.json());
    }

    @Test
    void aReplyWithoutAnyFenceIsAnError() {
        PlanReplyExtractor.Extracted e = PlanReplyExtractor.extract("ごめん、わからない");
        assertEquals("no json block", e.error());
        assertNull(e.json());
    }

    @Test
    void anUnclosedFenceIsAnError() {
        PlanReplyExtractor.Extracted e = PlanReplyExtractor.extract(
                "つくるよ\n```json\n{\"ops\":[]}");
        assertEquals("no json block", e.error());
    }

    @Test
    void anEmptyJsonBlockIsAnError() {
        PlanReplyExtractor.Extracted e = PlanReplyExtractor.extract(
                "つくるよ\n```json\n\n```");
        assertEquals("empty json block", e.error());
    }

    @Test
    void aJsonArrayIsNotAPlanObject() {
        PlanReplyExtractor.Extracted e = PlanReplyExtractor.extract(
                "つくるよ\n```json\n[1,2]\n```");
        assertEquals("not a json object", e.error());
        assertNull(e.json());
    }

    @Test
    void aPlanLongerThanTheCapIsAnError() {
        String big = "{" + "x".repeat(PlanReplyExtractor.MAX_PLAN_CHARS - 1) + "}";
        assertEquals(PlanReplyExtractor.MAX_PLAN_CHARS + 1, big.length());
        PlanReplyExtractor.Extracted e = PlanReplyExtractor.extract("```json\n" + big + "\n```");
        assertEquals("json over " + PlanReplyExtractor.MAX_PLAN_CHARS + " chars", e.error());
    }

    @Test
    void aPlanAtTheCapIsAccepted() {
        String big = "{" + "x".repeat(PlanReplyExtractor.MAX_PLAN_CHARS - 2) + "}";
        assertEquals(PlanReplyExtractor.MAX_PLAN_CHARS, big.length());
        PlanReplyExtractor.Extracted e = PlanReplyExtractor.extract("```json\n" + big + "\n```");
        assertNull(e.error());
        assertEquals(big, e.json());
    }

    @Test
    void windowsLineEndingsExtractTheSame() {
        PlanReplyExtractor.Extracted e = PlanReplyExtractor.extract(
                "やねが あかい こやを つくるよ\r\n```json\r\n{\"ops\":[]}\r\n```");
        assertNull(e.error());
        assertEquals("{\"ops\":[]}", e.json());
        assertEquals("やねが あかい こやを つくるよ", e.childSays());
    }
}
