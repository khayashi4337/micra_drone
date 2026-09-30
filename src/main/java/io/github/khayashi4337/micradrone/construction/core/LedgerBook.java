package io.github.khayashi4337.micradrone.construction.core;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** The material ledgers of every job, one ledger per job id (04 F-7). */
public final class LedgerBook {
    // sorted: the public view's job-id order must not depend on the insertion order
    private final Map<String, MaterialLedger> ledgers = new TreeMap<>();

    /** The job's ledger, created on first use. */
    public MaterialLedger of(String jobId) {
        return ledgers.computeIfAbsent(Objects.requireNonNull(jobId, "jobId"), k -> new MaterialLedger());
    }

    public Optional<MaterialLedger> find(String jobId) {
        return Optional.ofNullable(ledgers.get(jobId));
    }

    public Map<String, MaterialLedger> all() {
        return Collections.unmodifiableMap(ledgers);
    }

    /** Restore: puts a ledger read back from disk under its job id. */
    public void put(String jobId, MaterialLedger ledger) {
        ledgers.put(Objects.requireNonNull(jobId, "jobId"), Objects.requireNonNull(ledger, "ledger"));
    }
}
