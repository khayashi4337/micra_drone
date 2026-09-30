package io.github.khayashi4337.micradrone.build.model;

import java.util.List;
import java.util.Objects;

/** Where a plan revision came from (which stage and model). Not part of the plan's content hash. */
public record Provenance(String stageId, String modelId, String promptHash, List<String> imageIds, long createdAtMillis) {
    public static final Provenance NONE = new Provenance("", "", "", List.of(), 0L);

    public Provenance {
        stageId = Objects.requireNonNullElse(stageId, "");
        modelId = Objects.requireNonNullElse(modelId, "");
        promptHash = Objects.requireNonNullElse(promptHash, "");
        imageIds = List.copyOf(Objects.requireNonNullElse(imageIds, List.of()));
    }
}
