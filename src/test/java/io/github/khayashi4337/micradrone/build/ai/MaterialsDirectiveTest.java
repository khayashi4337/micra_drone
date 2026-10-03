package io.github.khayashi4337.micradrone.build.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * The materials frame an AI reply may carry beside the plan (Task 27b): a fenced
 * {@code ```materials} block holding {@code {"materials":{"inventory":bool,"exclude":[ids],
 * "include":[ids]}}}. The extraction is all-or-nothing - one bad field discards the whole
 * directive - and item ids must be ones the catalog knows.
 */
class MaterialsDirectiveTest {
    /** The catalog the tests use: only these ids "exist". */
    private static final Predicate<String> KNOWN = Set.of(
            "minecraft:diamond", "minecraft:emerald", "minecraft:cobblestone",
            "minecraft:stone")::contains;

    private static String reply(String body) {
        return "こやを つくるよ\n```json\n{\"ops\":[]}\n```\n```materials\n" + body + "\n```";
    }

    private static MaterialsDirective.Extracted extract(String reply) {
        return MaterialsDirective.extract(reply, KNOWN);
    }

    @Test
    void aReplyWithoutAMaterialsFrameFindsNothing() {
        MaterialsDirective.Extracted e = extract("こやを つくるよ\n```json\n{\"ops\":[]}\n```");
        assertFalse(e.found());
        assertNull(e.directive());
    }

    @Test
    void aValidDirectiveParsesEveryField() {
        MaterialsDirective.Extracted e = extract(reply(
                "{\"materials\":{\"inventory\":true,\"exclude\":[\"minecraft:diamond\"],"
                        + "\"include\":[\"minecraft:emerald\"]}}"));
        assertTrue(e.found());
        MaterialsDirective.Directive d = e.directive();
        assertEquals(Boolean.TRUE, d.inventory());
        assertEquals(List.of("minecraft:diamond"), d.exclude());
        assertEquals(List.of("minecraft:emerald"), d.include());
    }

    @Test
    void anEmptyMaterialsObjectChangesNothing() {
        MaterialsDirective.Extracted e = extract(reply("{\"materials\":{}}"));
        assertTrue(e.found());
        assertTrue(e.directive().isEmpty(), "an empty directive is valid but carries no change");
    }

    @Test
    void aMaterialsFrameBeforeThePlanIsStillFound() {
        MaterialsDirective.Extracted e = extract(
                "こや\n```materials\n{\"materials\":{\"inventory\":false}}\n```\n```json\n{\"ops\":[]}\n```");
        assertTrue(e.found());
        assertEquals(Boolean.FALSE, e.directive().inventory());
    }

    @Test
    void aSingleBadFieldDiscardsTheWholeDirective() {
        // inventory is a string here, the other fields are fine: nothing may survive
        MaterialsDirective.Extracted e = extract(reply(
                "{\"materials\":{\"inventory\":\"yes\",\"exclude\":[\"minecraft:diamond\"]}}"));
        assertTrue(e.found());
        assertNull(e.directive(), "a partially valid directive must be dropped whole");
    }

    @Test
    void anUnknownItemDiscardsTheWholeDirective() {
        MaterialsDirective.Extracted e = extract(reply(
                "{\"materials\":{\"exclude\":[\"minecraft:diamond\",\"minecraft:bedrock\"]}}"));
        assertNull(e.directive(), "bedrock is not in the test catalog, so everything is dropped");
    }

    @Test
    void anUnknownInnerFieldDiscardsTheWholeDirective() {
        assertNull(extract(reply("{\"materials\":{\"speed\":2}}")).directive());
    }

    @Test
    void anUnknownTopLevelFieldDiscardsTheWholeDirective() {
        assertNull(extract(reply("{\"materials\":{},\"other\":1}")).directive());
    }

    @Test
    void aNonObjectMaterialsValueIsRejected() {
        assertNull(extract(reply("{\"materials\":\"minecraft:diamond\"}")).directive());
        assertNull(extract(reply("[\"minecraft:diamond\"]")).directive());
        assertNull(extract(reply("not json")).directive());
    }

    @Test
    void moreThanTheItemCapIsRejected() {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < MaterialsDirective.MAX_ITEMS + 1; i++) {
            ids.append(i == 0 ? "" : ",").append("\"minecraft:stone\"");
        }
        assertNull(extract(reply("{\"materials\":{\"exclude\":[" + ids + "]}}")).directive());
    }

    @Test
    void anItemIdLongerThanTheCapIsRejected() {
        String longId = "minecraft:" + "a".repeat(MaterialsDirective.MAX_ITEM_ID_CHARS);
        assertNull(extract(reply("{\"materials\":{\"exclude\":[\"" + longId + "\"]}}")).directive());
    }

    @Test
    void aBodyLongerThanTheCapIsRejected() {
        // padding inside the array keeps the JSON valid while pushing the body over the cap
        String padded = "{\"materials\":{\"exclude\":[" + " ".repeat(MaterialsDirective.MAX_DIRECTIVE_CHARS)
                + "\"minecraft:stone\"]}}";
        assertNull(extract(reply(padded)).directive());
    }

    @Test
    void twoMaterialsFramesAreRejected() {
        assertNull(extract(
                "```materials\n{\"materials\":{\"inventory\":true}}\n```\n"
                        + "```materials\n{\"materials\":{\"inventory\":false}}\n```").directive(),
                "which one would win would be a guess, so both are dropped");
    }

    @Test
    void includeTakesTheItemBackOutOfExclude() {
        MaterialsDirective.Extracted e = extract(reply(
                "{\"materials\":{\"exclude\":[\"minecraft:diamond\",\"minecraft:cobblestone\"],"
                        + "\"include\":[\"minecraft:diamond\"]}}"));
        assertEquals(List.of("minecraft:cobblestone"), e.directive().exclude(),
                "include undoes the same directive's exclude");
        assertEquals(List.of("minecraft:diamond"), e.directive().include());
    }

    @Test
    void aNonStringListEntryIsRejected() {
        assertNull(extract(reply("{\"materials\":{\"exclude\":[5]}}")).directive());
        assertNull(extract(reply("{\"materials\":{\"exclude\":\"minecraft:diamond\"}}")).directive());
    }

    @Test
    void anUnfencedObjectIsNotADirective() {
        assertFalse(extract("{\"materials\":{\"inventory\":true}}").found(),
                "only a fenced materials block counts - loose text never switches materials");
    }
}
