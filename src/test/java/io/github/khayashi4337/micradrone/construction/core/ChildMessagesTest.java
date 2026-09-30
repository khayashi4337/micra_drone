package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ChildMessagesTest {
    private static final Path LANG = Path.of("src/main/resources/assets/micradrone/lang");
    /** The 小3 persona reads mostly hiragana: at most this share of a Japanese message may be kanji. */
    private static final double MAX_KANJI_SHARE = 0.30;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> lang(String file) throws IOException {
        return (Map<String, Object>) MiniJson.parse(Files.readString(LANG.resolve(file), StandardCharsets.UTF_8));
    }

    private static Set<String> buildKeys(Map<String, Object> lang) {
        Set<String> out = new TreeSet<>();
        for (String k : lang.keySet()) {
            if (k.startsWith(ChildMessages.PREFIX)) {
                out.add(k);
            }
        }
        return out;
    }

    @Test
    void everyKeyTheCodeUsesExistsInJapaneseAndEnglishAndNothingElse() throws IOException {
        Set<String> code = new TreeSet<>(ChildMessages.allKeys());
        assertEquals(code, buildKeys(lang("ja_jp.json")));
        assertEquals(code, buildKeys(lang("en_us.json")));
    }

    @Test
    void everyStatePauseRejectionAndControlHasAKey() {
        for (JobState s : JobState.values()) {
            assertTrue(ChildMessages.allKeys().contains(ChildMessages.state(s)), s.name());
        }
        for (PauseReason r : PauseReason.values()) {
            assertTrue(ChildMessages.allKeys().contains(ChildMessages.pause(r)), r.name());
        }
        for (ApprovalRejection r : ApprovalRejection.values()) {
            assertTrue(ChildMessages.allKeys().contains(ChildMessages.rejection(r)), r.name());
        }
        for (ControlResult c : ControlResult.values()) {
            assertTrue(ChildMessages.allKeys().contains(ChildMessages.control(c)), c.name());
        }
        assertEquals(ChildMessages.ISSUE_OTHER, ChildMessages.issue(IssueCode.E_ROT_CONFLICT), "not a P4 message: generic");
        assertEquals("micradrone.build.issue.e_site_blocked", ChildMessages.issue(IssueCode.E_SITE_BLOCKED));
    }

    @Test
    void theJapaneseIsEasyToRead() throws IOException {
        for (Map.Entry<String, Object> e : lang("ja_jp.json").entrySet()) {
            if (!e.getKey().startsWith(ChildMessages.PREFIX)) {
                continue;
            }
            String text = (String) e.getValue();
            long kanji = text.codePoints().filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN).count();
            long letters = text.codePoints().filter(Character::isLetter).count();
            assertTrue(letters == 0 || kanji / (double) letters <= MAX_KANJI_SHARE, e.getKey() + ": " + text);
            assertFalse(text.matches(".*\\b[EW]-[A-Z-]+\\b.*"), "no raw issue codes in a child's text: " + e.getKey());
        }
    }

    @Test
    void theLinesBuiltForAChildNeverCarryAnIssueCode() {
        for (IssueCode c : IssueCode.values()) {
            MessageKey line = ChildMessages.issueLine(Issue.of(c, java.util.List.of("wall-n"), "x"));
            assertFalse(line.key().contains(c.label()), c.label());
            for (String arg : line.args()) {
                assertFalse(arg.matches(".*\\b[EW]-[A-Z-]+.*"), "an issue code leaked into a child's line: " + arg);
            }
        }
        assertEquals(java.util.List.of("3"), ChildMessages.partial(3).args(), "a count, not \"missing=2 conflict=1\"");
    }
}
