package io.github.khayashi4337.micradrone.construction.core;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What the client sends to approve (04 F-3's ApprovePlanPayload): the manifest hash the server computed, the
 * dimension, the player asking, the risks accepted and the confirmations ticked. The expiry lives on the
 * {@link PendingApproval}, not here.
 */
public record ApprovalRequest(String manifestHash, String dimension, UUID playerUuid, List<AcceptedRisk> acceptedRisks,
                              Confirmations confirmations) {
    public ApprovalRequest {
        Objects.requireNonNull(manifestHash, "manifestHash");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(playerUuid, "playerUuid");
        acceptedRisks = List.copyOf(acceptedRisks);
        Objects.requireNonNull(confirmations, "confirmations");
    }
}
