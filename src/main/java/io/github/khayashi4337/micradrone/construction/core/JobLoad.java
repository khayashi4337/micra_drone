package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a job's files came back as (04 F-2): the whole record and its material ledger, or the reasons they could not be
 * trusted (the manifest is still handed over when it reads, so a repair can be built from it).
 */
public sealed interface JobLoad {
    record Loaded(JobRecord record, MaterialLedger ledger) implements JobLoad {
        public Loaded {
            Objects.requireNonNull(record, "record");
            Objects.requireNonNull(ledger, "ledger");
        }
    }

    record Broken(PlacementManifest manifestOrNull, Map<String, String> nodeTypes, List<String> reasons)
            implements JobLoad {
        public Broken {
            nodeTypes = Map.copyOf(Objects.requireNonNull(nodeTypes, "nodeTypes"));
            reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        }
    }
}
