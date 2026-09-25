package io.github.khayashi4337.micradrone.build.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class IssueTest {
    @Test
    void severityComesFromTheLabelPrefix() {
        assertEquals(Severity.ERROR, IssueCode.E_PARAM_RANGE.severity());
        assertEquals(Severity.WARN, IssueCode.W_UNMODELED.severity());
        assertEquals("E-PARAM-RANGE", IssueCode.E_PARAM_RANGE.label());
    }

    @Test
    void onlyWarningsAndClogRiskAreAcceptable() {
        for (IssueCode code : IssueCode.values()) {
            boolean expected = code.label().startsWith("W-") || code == IssueCode.E_CLOG_RISK;
            assertEquals(expected, code.acceptable(), code.label());
        }
        assertFalse(IssueCode.E_BLOCK_FORBIDDEN.acceptable());
        assertTrue(IssueCode.E_CLOG_RISK.acceptable());
    }

    @Test
    void fromLabelRoundTripsEveryCode() {
        for (IssueCode code : IssueCode.values()) {
            assertEquals(code, IssueCode.fromLabel(code.label()).orElseThrow());
        }
        assertTrue(IssueCode.fromLabel("E-NOPE").isEmpty());
    }

    @Test
    void issueIdCombinesCodeSubjectsAndKey() {
        Issue a = Issue.of(IssueCode.E_STRESS_OVER, List.of("net-3"), "over");
        assertEquals("E-STRESS-OVER:net-3", a.id());
        Issue b = Issue.of(IssueCode.E_PARAM_RANGE, "height", List.of("wall-1"), "too high", Map.of("max", "16"), List.of());
        assertEquals("E-PARAM-RANGE:wall-1#height", b.id());
        assertEquals("E-ANCHOR:a#parent", Issue.of(IssueCode.E_ANCHOR, "parent", List.of("a"), "m").id());
        assertEquals("16", b.data().get("max"));
        assertTrue(b.isError());
        assertFalse(Issue.of(IssueCode.W_UNMODELED, List.of("x"), "m").isError());
        assertEquals("E-SCHEMA:", Issue.of(IssueCode.E_SCHEMA, List.of(), "m").id());
    }

    @Test
    void collectionsAreCopiedAndImmutable() {
        Issue i = Issue.of(IssueCode.E_ANCHOR, "", List.of("a"), "m", Map.of("k", "v"),
                List.of(new FixHint("USE", Map.of("x", "1"))));
        assertThrows(UnsupportedOperationException.class, () -> i.subjects().add("b"));
        assertThrows(UnsupportedOperationException.class, () -> i.data().put("z", "z"));
        assertThrows(UnsupportedOperationException.class, () -> i.hints().clear());
        assertEquals("1", i.hints().get(0).args().get("x"));
    }

    /** The enum and the design document's table (05, section 4.1) must list exactly the same codes. */
    @Test
    void enumMatchesTheDesignDocumentTable() throws IOException {
        Path doc = Path.of("docs/design/nl_factory_builder/05_parts_and_analyzers.md");
        String text = Files.readString(doc, StandardCharsets.UTF_8);
        int start = text.indexOf("### 4.1 `IssueCode`");
        int end = text.indexOf("### 4.2", start);
        String section = text.substring(start, end);
        Set<String> documented = new HashSet<>();
        Pattern row = Pattern.compile("^\\| ((?:`[EW]-[A-Z-]+`/?)+) \\|", Pattern.MULTILINE);
        Matcher m = row.matcher(section);
        while (m.find()) {
            Matcher token = Pattern.compile("`([EW]-[A-Z-]+)`").matcher(m.group(1));
            while (token.find()) {
                documented.add(token.group(1));
            }
        }
        Set<String> coded = new HashSet<>();
        for (IssueCode code : IssueCode.values()) {
            coded.add(code.label());
        }
        assertEquals(documented, coded);
    }
}
