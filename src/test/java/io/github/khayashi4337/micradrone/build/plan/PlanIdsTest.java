package io.github.khayashi4337.micradrone.build.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.TestParts;
import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.PlanIds;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.PlanOp;
import io.github.khayashi4337.micradrone.build.model.PlanPatch;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanIdsTest {
    @Test
    void anIdIsOneToFortyEightLowercaseLettersDigitsOrHyphens() {
        assertTrue(PlanIds.isValid("a"));
        assertTrue(PlanIds.isValid("press-1"));
        assertTrue(PlanIds.isValid("0-9-z"));
        assertTrue(PlanIds.isValid("a".repeat(PlanIds.MAX_LENGTH)));
        assertEquals(48, PlanIds.MAX_LENGTH);
        assertFalse(PlanIds.isValid("a".repeat(PlanIds.MAX_LENGTH + 1)));
        assertFalse(PlanIds.isValid(""));
        assertFalse(PlanIds.isValid("Press"));
        assertFalse(PlanIds.isValid("press_1"));
        assertFalse(PlanIds.isValid("a/b"));
        assertFalse(PlanIds.isValid("a b"));
        assertFalse(PlanIds.isValid("a\n"));
        assertFalse(PlanIds.isValid("プレス"));
    }

    @Test
    void thePatcherStillRefusesWithTheSameCodeAndMessageAsBefore() {
        PlanPatcher patcher = new PlanPatcher(TestParts.registry(), TemplateBundle.EMPTY);
        PlanNode bad = new PlanNode("Bad_Id", "micra:pillar", null, new Anchor.Absolute(new LocalPos(0, 0, 0), Rot.NONE),
                Map.of(), Set.of(), "");
        PatchResult r = patcher.apply(SemanticPlan.empty("p"), new PlanPatch("p", 0, "t", List.of(new PlanOp.AddNode(bad))));
        assertEquals(1, r.issues().size());
        assertEquals("E-ID-INVALID:Bad_Id", r.issues().get(0).id());
        assertEquals("IDは半角の小文字・数字・ハイフンで48字以内にしてください: Bad_Id", r.issues().get(0).message());
        assertEquals(r.issues().get(0).message(), PlanIds.invalidMessage("Bad_Id"));
    }
}
