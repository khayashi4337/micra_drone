package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.Placement;
import java.util.Objects;

/**
 * One placement step of a job program: the manifest index, the ledger key its material is charged under
 * (a repair round's key differs from the manifest index, so a re-placed block is charged again under its own key),
 * and the placement itself.
 */
public record PutItem(int index, int ledgerKey, Placement placement) {
    public PutItem {
        Objects.requireNonNull(placement, "placement");
    }
}
