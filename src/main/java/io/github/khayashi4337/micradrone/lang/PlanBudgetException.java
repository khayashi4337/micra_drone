package io.github.khayashi4337.micradrone.lang;

/**
 * A recorder/PlanApi budget refusal: a limit on how much one run may keep, not a script
 * mistake and not an internal bug. The command dispatcher reports it as E-SCRIPT-LIMIT
 * through {@link PlanLimitException}; every other IllegalArgumentException a command raises
 * stays a malformed value (E-SCHEMA).
 */
public class PlanBudgetException extends IllegalArgumentException {
    public PlanBudgetException(String message) {
        super(message);
    }
}
