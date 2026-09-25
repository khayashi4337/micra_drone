package io.github.khayashi4337.micradrone.build.parts;

/** What must exist after an assembly step. The kind depends on the assembly (settled by spike S-8). */
public sealed interface AssemblyExpectation {
    record ContraptionExpectation(int entityCount, int movedBlockCount) implements AssemblyExpectation {
    }

    record SubLevelExpectation(int subLevelCount, int movedBlockCount) implements AssemblyExpectation {
    }
}
