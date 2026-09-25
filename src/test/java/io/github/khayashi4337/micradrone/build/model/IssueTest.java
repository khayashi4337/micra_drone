package io.github.khayashi4337.micradrone.build.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
        assertFalse(IssueCode.E_BLOCK_FORBIDDEN.acceptable());
        assertTrue(IssueCode.E_CLOG_RISK.acceptable());

        Set<String> expectedAcceptable = Set.of(
            "W-UNMODELED", "W-STRESS-MARGIN", "W-OVERSIZED-POWER", "W-NO-RECIPE",
            "W-DECOR-COLLIDE", "W-FUEL-SUPPLY", "W-ASSEMBLY-AWAY", "W-NO-SETUP",
            "W-DYNAMIC-PART", "E-CLOG-RISK"
        );

        Set<String> actualAcceptable = new HashSet<>();
        for (IssueCode code : IssueCode.values()) {
            if (code.acceptable()) {
                actualAcceptable.add(code.label());
            }
        }
        assertEquals(expectedAcceptable, actualAcceptable);

        // Verify Issue.of propagates the acceptability from the code
        Issue errorIssue = Issue.of(IssueCode.E_BLOCK_FORBIDDEN, List.of("x"), "m");
        assertFalse(errorIssue.acceptable());
        assertEquals(Severity.ERROR, errorIssue.severity());

        Issue warningIssue = Issue.of(IssueCode.W_UNMODELED, List.of("y"), "m");
        assertTrue(warningIssue.acceptable());
        assertEquals(Severity.WARN, warningIssue.severity());
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
        // Test Issue with mutable inputs
        ArrayList<String> mutableSubjects = new ArrayList<>();
        mutableSubjects.add("a");

        HashMap<String, String> mutableData = new LinkedHashMap<>();
        mutableData.put("c", "3");
        mutableData.put("b", "2");
        mutableData.put("a", "1");

        ArrayList<FixHint> mutableHints = new ArrayList<>();
        LinkedHashMap<String, String> mutableHintArgs = new LinkedHashMap<>();
        mutableHintArgs.put("y", "2");
        mutableHintArgs.put("x", "1");
        mutableHints.add(new FixHint("USE", mutableHintArgs));

        Issue issue = Issue.of(IssueCode.E_ANCHOR, "", mutableSubjects, "m", mutableData, mutableHints);

        // Mutate the original collections after Issue construction
        mutableSubjects.add("b");
        mutableData.put("z", "z");
        mutableHints.clear();

        // Issue must be unchanged
        assertEquals(List.of("a"), issue.subjects());
        assertEquals(Map.of("a", "1", "b", "2", "c", "3"), issue.data());
        assertEquals(1, issue.hints().size());
        assertEquals("USE", issue.hints().get(0).kind());
        assertEquals("1", issue.hints().get(0).args().get("x"));

        // Returned collections must be unmodifiable
        assertThrows(UnsupportedOperationException.class, () -> issue.subjects().add("c"));
        assertThrows(UnsupportedOperationException.class, () -> issue.data().put("new", "value"));
        assertThrows(UnsupportedOperationException.class, () -> issue.hints().add(new FixHint("X", Map.of())));

        // Data keys must be sorted even though input was reverse-ordered
        assertEquals(List.of("a", "b", "c"), new ArrayList<>(issue.data().keySet()));

        // Test FixHint with mutable args
        LinkedHashMap<String, String> mutableFixArgs = new LinkedHashMap<>();
        mutableFixArgs.put("z", "z");
        mutableFixArgs.put("y", "y");
        mutableFixArgs.put("x", "x");

        FixHint hint = new FixHint("TEST", mutableFixArgs);
        mutableFixArgs.put("w", "w");

        // FixHint.args must be unchanged
        assertEquals(Map.of("x", "x", "y", "y", "z", "z"), hint.args());

        // FixHint.args must be unmodifiable
        assertThrows(UnsupportedOperationException.class, () -> hint.args().put("new", "val"));

        // Args keys must be sorted
        assertEquals(List.of("x", "y", "z"), new ArrayList<>(hint.args().keySet()));
    }

    /** The enum and the design document's table (05, section 4.1) must list exactly the same codes. */
    @Test
    void enumMatchesTheDesignDocumentTable() throws IOException {
        Path doc = Path.of("docs/design/nl_factory_builder/05_parts_and_analyzers.md");
        String text = Files.readString(doc, StandardCharsets.UTF_8);
        int start = text.indexOf("### 4.1 `IssueCode`");
        int end = text.indexOf("### 4.2", start);
        assertTrue(start >= 0, "design doc heading '### 4.1 `IssueCode`' not found");
        assertTrue(end > start, "design doc heading '### 4.2' not found after 4.1");
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

    @Test
    void issueConstructorEnforcesInvariants() {
        // Correct construction via Issue.of should work
        Issue valid = Issue.of(IssueCode.E_BLOCK_FORBIDDEN, List.of("x"), "m");
        assertEquals(Severity.ERROR, valid.severity());
        assertFalse(valid.acceptable());

        // Direct constructor with mismatched severity must throw
        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class, () ->
            new Issue("id", IssueCode.E_BLOCK_FORBIDDEN, Severity.WARN, false, List.of(), "m", Map.of(), List.of())
        );
        assertTrue(ex1.getMessage().contains("E-BLOCK-FORBIDDEN"));

        // Direct constructor with mismatched acceptable must throw
        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class, () ->
            new Issue("id", IssueCode.E_BLOCK_FORBIDDEN, Severity.ERROR, true, List.of(), "m", Map.of(), List.of())
        );
        assertTrue(ex2.getMessage().contains("E-BLOCK-FORBIDDEN"));
    }
}
