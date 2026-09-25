package io.github.khayashi4337.micradrone.build.parts;

import io.github.khayashi4337.micradrone.build.model.Dir6;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import java.util.Set;

/** A connection point of a part, in the part's local frame. {@code accepts} lists conditions the other end must meet. */
public record PortSpec(String name, PortKind kind, LocalPos offset, Dir6 facing, Set<String> accepts) {
    public PortSpec {
        accepts = SortedCopies.set(accepts);
    }
}
