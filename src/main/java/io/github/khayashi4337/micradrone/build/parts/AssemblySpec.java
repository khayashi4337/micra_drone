package io.github.khayashi4337.micradrone.build.parts;

/** How a part that vanishes into a moving structure (windmill sails, airship) is assembled and undone. */
public record AssemblySpec(AssemblyKind kind, String triggerPort, AssemblyExpectation expect, String disassembleAction) {
}
