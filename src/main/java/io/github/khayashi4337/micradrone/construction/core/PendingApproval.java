package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.Issue;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A compiled plan waiting for its owner's approval (design 01, section 11.1), pinned to a survey digest. */
public record PendingApproval(String manifestHash, String dimension, UUID owner, long expiresTick, List<Issue> issues,
                              String surveyDigest) {
    public PendingApproval {
        Objects.requireNonNull(manifestHash, "manifestHash");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(owner, "owner");
        issues = List.copyOf(issues);
        Objects.requireNonNull(surveyDigest, "surveyDigest");
    }
}
