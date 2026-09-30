package io.github.khayashi4337.micradrone.build.parts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * One accepting and one rejecting case per keyword {@link MiniSchemaValidator} supports, so a check that was
 * dropped or misnamed cannot pass silently. A malformed schema (unknown keyword, unresolved $ref) fails at
 * construction, wherever in the tree it sits.
 */
class MiniSchemaValidatorTest {
    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(String schemaJson) {
        return (Map<String, Object>) MiniJson.parse(schemaJson);
    }

    private static List<String> check(String schemaJson, String valueJson) {
        return new MiniSchemaValidator(schema(schemaJson)).validate(MiniJson.parse(valueJson));
    }

    private static void accepts(String schemaJson, String valueJson) {
        assertEquals(List.of(), check(schemaJson, valueJson), "expected " + valueJson + " to pass " + schemaJson);
    }

    private static void rejects(String schemaJson, String valueJson) {
        assertFalse(check(schemaJson, valueJson).isEmpty(), "expected " + valueJson + " to fail " + schemaJson);
    }

    @Test
    void anUnknownKeywordFailsTheUpFrontWalk() {
        assertThrows(IllegalArgumentException.class, () -> new MiniSchemaValidator(schema("{\"tpye\":\"object\"}")));
        // a typo buried in a sub-schema a document might never reach is still found
        assertThrows(IllegalArgumentException.class, () -> new MiniSchemaValidator(schema(
                "{\"type\":\"object\",\"properties\":{\"a\":{\"minimun\":0}}}")));
        assertThrows(IllegalArgumentException.class, () -> new MiniSchemaValidator(schema(
                "{\"$defs\":{\"x\":{\"tpye\":\"integer\"}},\"type\":\"object\"}")));
    }

    @Test
    void anUnresolvedRefFailsTheUpFrontWalk() {
        assertThrows(IllegalArgumentException.class, () -> new MiniSchemaValidator(schema(
                "{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"#/$defs/missing\"}}}")));
        // a bad ref inside an unreachable $def is still found
        assertThrows(IllegalArgumentException.class, () -> new MiniSchemaValidator(schema(
                "{\"$defs\":{\"x\":{\"$ref\":\"#/$defs/nowhere\"}},\"type\":\"object\"}")));
        // a $ref that does not point into $defs at all
        assertThrows(IllegalArgumentException.class, () -> new MiniSchemaValidator(schema(
                "{\"$ref\":\"#/somewhere/else\"}")));
    }

    @Test
    void aDefsHolderIsAllowedAndRefResolves() {
        accepts("{\"$defs\":{\"x\":{\"type\":\"integer\"}},\"type\":\"object\","
                + "\"properties\":{\"a\":{\"$ref\":\"#/$defs/x\"}}}", "{\"a\":3}");
        rejects("{\"$defs\":{\"x\":{\"type\":\"integer\"}},\"type\":\"object\","
                + "\"properties\":{\"a\":{\"$ref\":\"#/$defs/x\"}}}", "{\"a\":\"s\"}");
    }

    @Test
    void oneOfNeedsExactlyOneMatch() {
        String schema = "{\"oneOf\":[{\"type\":\"object\",\"required\":[\"a\"],\"properties\":{\"a\":{\"const\":1}}},"
                + "{\"type\":\"object\",\"required\":[\"b\"],\"properties\":{\"b\":{\"const\":2}}}]}";
        accepts(schema, "{\"a\":1}");
        rejects(schema, "{\"c\":3}");
        rejects(schema, "{\"a\":1,\"b\":2}");
    }

    @Test
    void constMatchesOnlyTheConstant() {
        accepts("{\"const\":\"add_node\"}", "\"add_node\"");
        rejects("{\"const\":\"add_node\"}", "\"addNode\"");
    }

    @Test
    void enumMatchesOnlyListedValues() {
        accepts("{\"enum\":[\"north\",\"east\"]}", "\"east\"");
        rejects("{\"enum\":[\"north\",\"east\"]}", "\"up\"");
    }

    @Test
    void typeChecksEachJsonTypeAndUnions() {
        accepts("{\"type\":\"object\"}", "{}");
        rejects("{\"type\":\"object\"}", "[]");
        accepts("{\"type\":\"array\"}", "[]");
        rejects("{\"type\":\"array\"}", "{}");
        accepts("{\"type\":\"string\"}", "\"x\"");
        rejects("{\"type\":\"string\"}", "1");
        accepts("{\"type\":\"boolean\"}", "true");
        rejects("{\"type\":\"boolean\"}", "\"true\"");
        accepts("{\"type\":\"null\"}", "null");
        rejects("{\"type\":\"null\"}", "0");
        accepts("{\"type\":\"integer\"}", "3");
        rejects("{\"type\":\"integer\"}", "3.5");
        accepts("{\"type\":\"number\"}", "3.5");
        rejects("{\"type\":\"number\"}", "\"x\"");
        accepts("{\"type\":[\"string\",\"null\"]}", "null");
        rejects("{\"type\":[\"string\",\"null\"]}", "7");
    }

    @Test
    void minimumAndMaximumBoundNumbers() {
        accepts("{\"type\":\"integer\",\"minimum\":3,\"maximum\":9}", "5");
        rejects("{\"type\":\"integer\",\"minimum\":3}", "2");
        rejects("{\"type\":\"integer\",\"maximum\":9}", "10");
    }

    @Test
    void maxLengthBoundsStrings() {
        accepts("{\"type\":\"string\",\"maxLength\":3}", "\"abc\"");
        rejects("{\"type\":\"string\",\"maxLength\":3}", "\"abcd\"");
    }

    @Test
    void patternBoundsStrings() {
        accepts("{\"type\":\"string\",\"pattern\":\"^[a-z]+$\"}", "\"abc\"");
        rejects("{\"type\":\"string\",\"pattern\":\"^[a-z]+$\"}", "\"Abc\"");
    }

    @Test
    void minItemsAndMaxItemsBoundArrays() {
        accepts("{\"type\":\"array\",\"minItems\":1,\"maxItems\":2}", "[1,2]");
        rejects("{\"type\":\"array\",\"minItems\":1}", "[]");
        rejects("{\"type\":\"array\",\"maxItems\":2}", "[1,2,3]");
    }

    @Test
    void itemsChecksEveryElement() {
        accepts("{\"type\":\"array\",\"items\":{\"type\":\"integer\"}}", "[1,2]");
        rejects("{\"type\":\"array\",\"items\":{\"type\":\"integer\"}}", "[1,\"x\"]");
    }

    @Test
    void requiredAndPropertiesCheckObjects() {
        String schema = "{\"type\":\"object\",\"required\":[\"a\"],\"properties\":{\"a\":{\"type\":\"integer\"}}}";
        accepts(schema, "{\"a\":1}");
        rejects(schema, "{}");
        rejects(schema, "{\"a\":\"x\"}");
    }

    @Test
    void additionalPropertiesFalseForbidsExtras() {
        String schema = "{\"type\":\"object\",\"properties\":{\"a\":{}},\"additionalProperties\":false}";
        accepts(schema, "{\"a\":1}");
        rejects(schema, "{\"b\":1}");
    }

    @Test
    void additionalPropertiesAsASchemaChecksExtras() {
        String schema = "{\"type\":\"object\",\"additionalProperties\":{\"type\":\"string\"}}";
        accepts(schema, "{\"x\":\"s\"}");
        rejects(schema, "{\"x\":1}");
    }
}
