package io.github.khayashi4337.micradrone.build.plan;

import io.github.khayashi4337.micradrone.build.model.LocalPos;
import java.util.Optional;

/** Turns a slot id into a local position. Slots come from the building analysis (phase P6); until then none resolve. */
@FunctionalInterface
public interface SlotResolver {
    SlotResolver NONE = slotId -> Optional.empty();

    Optional<LocalPos> resolve(String slotId);
}
