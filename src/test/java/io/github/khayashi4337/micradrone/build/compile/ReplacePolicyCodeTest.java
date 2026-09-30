package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ReplacePolicyCodeTest {
    @Test
    void everyPolicyRoundTripsThroughItsCode() {
        for (ReplacePolicy p : new ReplacePolicy[]{ReplacePolicy.AIR_ONLY, ReplacePolicy.REPLACEABLE, ReplacePolicy.TERRAFORM,
                new ReplacePolicy.Expect("minecraft:dirt")}) {
            assertEquals(p, ReplacePolicy.fromCode(p.code()));
        }
        assertEquals("terraform", ReplacePolicy.TERRAFORM.code());
        assertThrows(IllegalArgumentException.class, () -> ReplacePolicy.fromCode("anything"));
        assertThrows(IllegalArgumentException.class, () -> ReplacePolicy.fromCode("expect:"));
    }
}
