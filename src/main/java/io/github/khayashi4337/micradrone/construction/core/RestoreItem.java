package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One removal step of a job program: restore the position to {@link #restoreTo} when it still shows
 * {@link #expectedNow} (volatile block-state properties are ignored by the check). {@link #sourceJobId} and
 * {@link #sourceLedgerKey} name the ledger that answers for the removed block; {@link #earlierSourceKeys} are the keys
 * the same job wrote at the position before (a ground cut under its own foundation), so a removal settles every key.
 * {@link #dropContents} empties a project container before it goes (F-7).
 */
public record RestoreItem(IntPos pos, BlockSpec expectedNow, Set<String> volatileProps, BlockSpec restoreTo,
                          String sourceJobId, int sourceLedgerKey, boolean dropContents, List<Integer> earlierSourceKeys) {
    public RestoreItem {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(expectedNow, "expectedNow");
        volatileProps = Set.copyOf(volatileProps);
        Objects.requireNonNull(restoreTo, "restoreTo");
        Objects.requireNonNull(sourceJobId, "sourceJobId");
        earlierSourceKeys = List.copyOf(earlierSourceKeys);
    }

    /** A position written once by the job: only the current block's own key is settled. */
    public RestoreItem(IntPos pos, BlockSpec expectedNow, Set<String> volatileProps, BlockSpec restoreTo,
                       String sourceJobId, int sourceLedgerKey, boolean dropContents) {
        this(pos, expectedNow, volatileProps, restoreTo, sourceJobId, sourceLedgerKey, dropContents, List.of());
    }

    /** Every ledger key this removal settles: the earlier keys first, the current block's key last. */
    public List<Integer> sourceKeys() {
        List<Integer> out = new ArrayList<>(earlierSourceKeys.size() + 1);
        out.addAll(earlierSourceKeys);
        out.add(sourceLedgerKey);
        return out;
    }
}
