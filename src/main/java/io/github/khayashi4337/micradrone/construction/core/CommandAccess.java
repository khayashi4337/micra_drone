package io.github.khayashi4337.micradrone.construction.core;

import java.util.UUID;

/**
 * Who may look at a job (04 F-4, D-12). Reading another owner's job — status or a world diff — is denied the
 * same way a missing id is, so a stranger never learns the job exists at all.
 */
public final class CommandAccess {
    private CommandAccess() {
    }

    /** {@code viewer} is null when the command source is not a player (the console is an operator anyway). */
    public static boolean mayInspect(UUID viewer, boolean operator, UUID owner) {
        return operator || viewer != null && viewer.equals(owner);
    }
}
