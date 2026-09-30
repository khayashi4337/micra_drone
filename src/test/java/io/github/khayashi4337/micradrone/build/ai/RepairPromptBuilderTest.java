package io.github.khayashi4337.micradrone.build.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RepairPromptBuilderTest {

    @Test
    void issuesBecomeBulletsAndTheBadPlanIsIncluded() {
        String prompt = RepairPromptBuilder.build("{\"ops\":[]}",
                List.of("E-FOO: x is bad", "E-BAR: y is worse"));
        assertTrue(prompt.contains("- E-FOO: x is bad"));
        assertTrue(prompt.contains("- E-BAR: y is worse"));
        assertTrue(prompt.contains("```json"), "the same one-fence rule is restated");
        assertTrue(prompt.contains("ちょうど1つ"));
        assertTrue(prompt.contains("{\"ops\":[]}"), "the previous plan goes back verbatim");
    }

    @Test
    void onlyTheFirstTenIssueLinesAreKept() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < RepairPromptBuilder.MAX_ISSUE_LINES + 2; i++) {
            lines.add("issue number " + i);
        }
        String prompt = RepairPromptBuilder.build("{}", lines);
        assertTrue(prompt.contains("- issue number " + (RepairPromptBuilder.MAX_ISSUE_LINES - 1)));
        assertFalse(prompt.contains("issue number " + RepairPromptBuilder.MAX_ISSUE_LINES));
        assertFalse(prompt.contains("issue number " + (RepairPromptBuilder.MAX_ISSUE_LINES + 1)));
    }

    @Test
    void anIssueLineLongerThanTheCapIsCut() {
        String line = "x".repeat(RepairPromptBuilder.MAX_ISSUE_LINE_CHARS) + "TAIL";
        String prompt = RepairPromptBuilder.build("{}", List.of(line));
        assertTrue(prompt.contains("x".repeat(RepairPromptBuilder.MAX_ISSUE_LINE_CHARS)));
        assertFalse(prompt.contains("TAIL"));
    }

    @Test
    void anIssueLineAtTheCapIsKeptWhole() {
        String line = "x".repeat(RepairPromptBuilder.MAX_ISSUE_LINE_CHARS - 4) + "TAIL";
        String prompt = RepairPromptBuilder.build("{}", List.of(line));
        assertTrue(prompt.contains(line));
    }

    @Test
    void theRepairPromptRestatesTheWakachiRule() {
        String prompt = RepairPromptBuilder.build("{\"ops\":[]}", List.of("E-FOO: x is bad"));
        assertTrue(prompt.contains(BuildPromptBuilder.WAKACHI_RULE),
                "the corrected reply keeps the same wakachi-gaki one-liner rule");
    }

    @Test
    void anEmptyIssueListIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RepairPromptBuilder.build("{}", List.of()));
    }
}
