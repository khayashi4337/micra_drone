package io.github.khayashi4337.micradrone.build.parts;

import io.github.khayashi4337.micradrone.build.model.Dir6;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.SortedCopies;
import java.util.Objects;
import java.util.Set;

/** A connection point of a part, in the part's local frame. {@code accepts} lists conditions the other end must meet. */
public record PortSpec(String name, PortKind kind, LocalPos offset, Dir6 facing, Set<String> accepts) {
    public PortSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(offset, "offset");
        Objects.requireNonNull(facing, "facing");
        accepts = SortedCopies.set(accepts);
    }
}
