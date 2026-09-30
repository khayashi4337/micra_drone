package io.github.khayashi4337.micradrone.construction.core;

/** The boxes the approver ticked on the approval screen (04 F-3): terraforming and destructive replacement. */
public record Confirmations(boolean terraform, boolean destructive) {
    public static final Confirmations NONE = new Confirmations(false, false);
}
