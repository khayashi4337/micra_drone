package io.github.khayashi4337.micradrone.construction.core;

/** Which check of the approval order (04 F-3, D-3, D-27, D-12) refused the request. */
public enum ApprovalRejection {
    NO_PENDING, NOT_OWNER, EXPIRED, HASH_MISMATCH, DIMENSION_MISMATCH, REGISTRY_CHANGED, SURVEY_MISMATCH,
    SURVEY_EXPIRED, RISK_NOT_ACCEPTABLE, BLOCKING_ISSUES, TERRAFORM_UNCONFIRMED, DESTRUCTIVE_UNCONFIRMED,
    ASSEMBLY_NOT_AVAILABLE
}
