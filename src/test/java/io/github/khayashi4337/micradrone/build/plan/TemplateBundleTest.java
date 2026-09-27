package io.github.khayashi4337.micradrone.build.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.parts.PartCategory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TemplateBundleTest {
    private static final String ID_PREFIX = "mod:t";
    private static final String MISSING_ID = "mod:nothing-like-this";
    /**
     * Templates in the speed test. A lookup by scanning the list compares about SPEED_TEMPLATES squared / 2 pairs of ids
     * (1.25 billion here, several seconds); the index needs one hash lookup each.
     */
    private static final int SPEED_TEMPLATES = 50_000;
    private static final Duration SPEED_BOUND = Duration.ofSeconds(2);

    private static ModuleTemplate template(String id, String displayNameKey) {
        return new ModuleTemplate(1, id, displayNameKey, PartCategory.MODULE, null, null, List.of(), List.of(), List.of(), null,
                null, Set.of());
    }

    @Test
    void findReturnsTheTemplateOfThatIdAndNothingForAnUnknownId() {
        ModuleTemplate a = template("mod:a", "k.a");
        ModuleTemplate b = template("mod:b", "k.b");
        TemplateBundle bundle = new TemplateBundle(List.of(a, b));
        assertSame(a, bundle.find("mod:a").orElseThrow());
        assertSame(b, bundle.find("mod:b").orElseThrow());
        assertEquals(Optional.empty(), bundle.find("mod:c"));
        assertEquals(Optional.empty(), bundle.find(null));
        assertEquals(Optional.empty(), TemplateBundle.EMPTY.find("mod:a"));
    }

    @Test
    void whenTwoTemplatesShareAnIdTheFirstOneIsFound() {
        ModuleTemplate first = template("mod:twin", "k.first");
        ModuleTemplate second = template("mod:twin", "k.second");
        assertSame(first, new TemplateBundle(List.of(first, second)).find("mod:twin").orElseThrow());
        assertSame(second, new TemplateBundle(List.of(second, first)).find("mod:twin").orElseThrow());
    }

    @Test
    void theBundleKeepsItsOwnCopyOfTheListItWasGiven() {
        List<ModuleTemplate> given = new ArrayList<>(List.of(template("mod:a", "k.a")));
        TemplateBundle bundle = new TemplateBundle(given);
        given.add(template("mod:late", "k.late"));
        assertEquals(1, bundle.templates().size());
        assertEquals(Optional.empty(), bundle.find("mod:late"), "the index describes the copy, not the caller's list");
        assertThrows(UnsupportedOperationException.class, () -> bundle.templates().add(template("mod:x", "k.x")));
    }

    @Test
    void twoBundlesOfEqualTemplatesAreEqualAsTheyWereWhenTheBundleWasARecord() {
        TemplateBundle one = new TemplateBundle(List.of(template("mod:a", "k.a"), template("mod:b", "k.b")));
        TemplateBundle same = new TemplateBundle(List.of(template("mod:a", "k.a"), template("mod:b", "k.b")));
        TemplateBundle reordered = new TemplateBundle(List.of(template("mod:b", "k.b"), template("mod:a", "k.a")));
        assertEquals(one, same);
        assertEquals(one.hashCode(), same.hashCode());
        assertEquals(one.toString(), same.toString());
        assertNotEquals(one, reordered);
        assertNotEquals(one, TemplateBundle.EMPTY);
        assertEquals(TemplateBundle.EMPTY, new TemplateBundle(List.of()));
    }

    @Test
    void verifyAgainstStillChecksEveryTemplateOfTheBundle() {
        ModuleTemplate a = template("mod:a", "k.a");
        TemplateBundle bundle = new TemplateBundle(List.of(a, template("mod:b", "k.b")));
        List<String> ids = bundle.verifyAgainst(Map.of("mod:a", a.hash())).stream().map(Issue::id).toList();
        assertEquals(List.of("E-TEMPLATE-UNVERIFIED:mod:b"), ids);
    }

    @Test
    void findIsALookupNotAScanOfTheList() {
        List<ModuleTemplate> templates = new ArrayList<>();
        for (int i = 0; i < SPEED_TEMPLATES; i++) {
            templates.add(template(ID_PREFIX + i, "k"));
        }
        TemplateBundle bundle = new TemplateBundle(templates);
        int found = assertTimeoutPreemptively(SPEED_BOUND, () -> {
            int count = 0;
            for (int i = 0; i < SPEED_TEMPLATES; i++) {
                if (bundle.find(ID_PREFIX + i).isPresent()) {
                    count++;
                }
            }
            for (int i = 0; i < SPEED_TEMPLATES; i++) {
                assertTrue(bundle.find(MISSING_ID + i).isEmpty());
            }
            return count;
        });
        assertEquals(SPEED_TEMPLATES, found);
    }
}
