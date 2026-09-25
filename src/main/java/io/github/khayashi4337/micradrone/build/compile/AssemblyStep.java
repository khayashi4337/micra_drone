package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.parts.AssemblyExpectation;
import io.github.khayashi4337.micradrone.build.parts.AssemblyKind;
import java.util.List;

/** Turns a group of placed blocks into a moving structure once they are all placed. */
public record AssemblyStep(String groupId, AssemblyKind kind, IntPos trigger, List<Integer> memberIndexes,
                           AssemblyExpectation expect) {
    public AssemblyStep {
        memberIndexes = List.copyOf(memberIndexes);
    }
}
