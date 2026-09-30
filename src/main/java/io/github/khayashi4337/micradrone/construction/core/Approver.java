package io.github.khayashi4337.micradrone.construction.core;

import java.util.UUID;

/**
 * Who is approving: their uuid, whether they are an operator, the dimension they are in, whether they are in
 * creative (D-4's material policy), and a policy the config forces (null when it does not).
 */
public record Approver(UUID uuid, boolean operator, String currentDimension, boolean creative,
                       MaterialPolicy forcedPolicy) {
}
