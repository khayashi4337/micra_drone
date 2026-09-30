package io.github.khayashi4337.micradrone.construction.core;

/** A warning the approver accepted by its issue id (design 01, section 11.1). */
public record AcceptedRisk(String issueId, String note) {
}
