package io.github.khayashi4337.micradrone.construction.core;

import java.util.Objects;

/** Which ledger entry a material operation settles: the job whose ledger it is, the ledger key, and the kind. */
public record OpKey(String jobId, int ledgerKey, MaterialOp op) {
    public OpKey {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(op, "op");
    }
}
