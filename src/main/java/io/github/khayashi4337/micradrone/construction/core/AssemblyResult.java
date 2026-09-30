package io.github.khayashi4337.micradrone.construction.core;

import java.util.Objects;

/**
 * The assembled individual of a group (design 01, section 4): P10 and P13 record it after assembly, and a later
 * disassembly or removal identifies the individual by this record.
 */
public record AssemblyResult(String groupId, String assembledId, int movedBlockCount, long atTick) {
    public AssemblyResult {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(assembledId, "assembledId");
    }
}
