package io.github.khayashi4337.micradrone.construction.core;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * The "change a claim's supply settings, durably" step of the consent commands (Task 27e). The
 * write that carries the change decides whether it happened: a save that fails rolls the in-memory
 * book back to what was there before, so what memory says never diverges from what a restart
 * would load - an {@code on} the disk never held must not keep looking granted.
 */
public final class SupplyChange {
    private SupplyChange() {
    }

    /**
     * Sets {@code next} on {@code book} for {@code claimId} and asks {@code save} to persist the
     * whole book; on a false save the previous settings are put back. Answers the save's verdict.
     * The caller keeps the order: validate permissions first, then hand the change to this.
     */
    public static boolean apply(SupplySettingsBook book, String claimId, SupplySettings next,
            BooleanSupplier save) {
        Objects.requireNonNull(book, "book");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(save, "save");
        SupplySettings before = book.of(claimId);
        book.set(claimId, next);
        if (!save.getAsBoolean()) {
            book.set(claimId, before);
            return false;
        }
        return true;
    }
}
