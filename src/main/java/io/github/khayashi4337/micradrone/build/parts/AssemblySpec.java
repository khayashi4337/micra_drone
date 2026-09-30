package io.github.khayashi4337.micradrone.build.parts;

import java.util.Objects;

/** How a part that vanishes into a moving structure (windmill sails, airship) is assembled and undone. */
public record AssemblySpec(AssemblyKind kind, String triggerPort, AssemblyExpectation expect, String disassembleAction) {
    /** {@code expect} may be null: an assembly step without a declared expectation still serializes {@code expect: null}. */
    public AssemblySpec {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(triggerPort, "triggerPort");
        Objects.requireNonNull(disassembleAction, "disassembleAction");
    }
}
